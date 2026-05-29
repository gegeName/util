package com.chat.myapplication

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.chat.picker.util.ZoomGestureHelper

class ZoomGestureActivity : AppCompatActivity() {
    val url="https://pics1.baidu.com/feed/4034970a304e251f9df1360c3e7ae6077d3e53c8.jpeg@f_auto?token=98ecf6f116fd8bb2e102458b8b11a00e"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_zoom_gesture)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val imageView1=findViewById<AppCompatImageView>(R.id.imageView1)
        Glide.with(imageView1).load(url).into(imageView1)
        ZoomGestureHelper.attach(imageView1)

    }
}