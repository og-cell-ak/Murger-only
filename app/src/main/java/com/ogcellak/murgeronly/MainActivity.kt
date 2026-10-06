package com.ogcellak.murgeronly
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import java.util.ArrayList

class MainActivity:Activity(){
 private val code=42; private val names=ArrayList<String>()
 private lateinit var list:LinearLayout; private lateinit var status:TextView; private lateinit var merge:Button
 override fun onCreate(b:Bundle?){super.onCreate(b);ui()}
 private fun ui(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
  root.addView(TextView(this).apply{text="Murger Only";textSize=28f;setTypeface(null,1)})
  root.addView(TextView(this).apply{text="Merge videos without re-encoding. Original streams are copied, so there is no quality loss from a new encode.";setPadding(0,10,0,18)})
  val add=Button(this).apply{text="Add videos"};val clear=Button(this).apply{text="Clear selection"}
  status=TextView(this).apply{text="No videos selected.";setPadding(0,12,0,12)}
  list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};merge=Button(this).apply{text="MERGE VIDEOS";isEnabled=false}
  root.addView(add);root.addView(clear);root.addView(status);root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));root.addView(merge)
  add.setOnClickListener{pick()};clear.setOnClickListener{names.clear();refresh()}
  merge.setOnClickListener{val i=Intent(this,MergeService::class.java).apply{action=MergeService.START;putStringArrayListExtra("uris",ArrayList(names))};if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);Toast.makeText(this,"Merge started in background.",Toast.LENGTH_LONG).show();merge.isEnabled=false}
  setContentView(root)
 }
 private fun pick(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="video/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},code)}
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r!=code||c!=RESULT_OK||d==null)return
  val u=ArrayList<Uri>();d.clipData?.let{x->for(i in 0 until x.itemCount)u.add(x.getItemAt(i).uri)}?:d.data?.let{u.add(it)}
  for(x in u)if(!names.contains(x.toString())){try{contentResolver.takePersistableUriPermission(x,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};names.add(x.toString())};refresh()
 }
 private fun refresh(){list.removeAllViews();names.forEachIndexed{n,u->list.addView(TextView(this).apply{text=(n+1).toString()+". "+u.substringAfterLast('/').takeLast(70);setPadding(0,7,0,7)})};status.text=if(names.isEmpty())"No videos selected." else names.size.toString()+" video(s) selected. Merge order is the list order.";merge.isEnabled=names.size>=2}
}
