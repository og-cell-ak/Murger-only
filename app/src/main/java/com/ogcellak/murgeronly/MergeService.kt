package com.ogcellak.murgeronly
import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class MergeService:Service(){
 companion object{const val START="START";private const val CH="merge";private const val ID=7001}
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH,"Video merging",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
  if(i?.action==START){startForeground(ID,notice("Preparing merge...",0));val uris=i.getStringArrayListExtra("uris")?:arrayListOf()
   scope.launch{try{Merger(this@MergeService).run(uris){p,s->notifyState(p,s)};notifyState(100,"Merge complete. Saved in Movies/Murger");delay(1800)}catch(t:Throwable){notifyState(0,"Merge failed: "+(t.message?:"unsupported video"));delay(3500)}finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}}}
  return START_NOT_STICKY
 }
 private fun notice(t:String,p:Int)=NotificationCompat.Builder(this,CH).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Murger Only").setContentText(t).setOngoing(p<100).setProgress(100,p.coerceIn(0,100),false).build()
 private fun notifyState(p:Int,t:String){getSystemService(NotificationManager::class.java).notify(ID,notice(t,p))}
 override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(i:Intent?)=null
}
private class Merger(private val c:Context){
 fun run(items:ArrayList<String>,progress:(Int,String)->Unit){
  require(items.size>=2){"Select at least two videos"}
  val first=extract(items[0]);val vt=find(first,"video/");val at=find(first,"audio/");require(vt>=0){"First video has no video track"}
  val vf=first.getTrackFormat(vt);val af=if(at>=0)first.getTrackFormat(at)else null
  val cv=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,"merged_"+System.currentTimeMillis()+".mp4");put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(Build.VERSION.SDK_INT>=29)put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Murger");if(Build.VERSION.SDK_INT>=29)put(MediaStore.Video.Media.IS_PENDING,1)}
  val uri=c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cv)?:error("Could not create output file")
  var pfd:ParcelFileDescriptor?=null
  try{
   pfd=c.contentResolver.openFileDescriptor(uri,"w")?:error("Could not open output file")
   val mux=MediaMuxer(pfd.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);val ov=mux.addTrack(vf);val oa=if(af!=null)mux.addTrack(af)else -1;mux.start()
   var vo=0L;var ao=0L
   for((idx,s) in items.withIndex()){
    val e=extract(s);val iv=find(e,"video/");val ia=find(e,"audio/");require(iv>=0){"Video "+(idx+1)+" has no video track"};require(compatible(vf,e.getTrackFormat(iv))){"Video "+(idx+1)+" has a different video format. Use matching codec/resolution/settings."};if(af!=null)require(ia>=0&&compatible(af,e.getTrackFormat(ia))){"Video "+(idx+1)+" has a different audio format."}
    progress((idx*100)/items.size,"Merging video "+(idx+1)+"/"+items.size);val vd=duration(e,iv);copy(e,iv,ov,mux,vo);if(oa>=0&&ia>=0){val ad=duration(e,ia);copy(e,ia,oa,mux,ao);ao+=ad};vo+=vd;e.release();progress(((idx+1)*100)/items.size,"Merging video "+(idx+1)+"/"+items.size)
   }
   mux.stop();mux.release();pfd?.close();pfd=null;if(Build.VERSION.SDK_INT>=29)c.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
  }catch(t:Throwable){c.contentResolver.delete(uri,null,null);throw t}finally{pfd?.close();first.release()}
 }
 private fun extract(s:String)=MediaExtractor().also{it.setDataSource(c,Uri.parse(s),null)}
 private fun find(e:MediaExtractor,p:String):Int{for(i in 0 until e.trackCount)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(p)==true)return i;return -1}
 private fun duration(e:MediaExtractor,t:Int)=if(e.getTrackFormat(t).containsKey(MediaFormat.KEY_DURATION))e.getTrackFormat(t).getLong(MediaFormat.KEY_DURATION)else 0L
 private fun compatible(a:MediaFormat,b:MediaFormat):Boolean{val k=listOf(MediaFormat.KEY_MIME,MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT);return k.all{if(a.containsKey(it)&&b.containsKey(it))a.getValue(it)==b.getValue(it)else true}}
 private fun copy(e:MediaExtractor,t:Int,dst:Int,m:MediaMuxer,off:Long){e.selectTrack(t);val b=ByteBuffer.allocateDirect(2*1024*1024);val info=MediaCodec.BufferInfo();while(true){b.clear();val n=e.readSampleData(b,0);if(n<0)break;info.offset=0;info.size=n;info.presentationTimeUs=e.sampleTime+off;info.flags=e.sampleFlags;m.writeSampleData(dst,b,info);e.advance()};e.unselectTrack(t)}
}
