package com.eudero.gano.data

import android.content.Context

internal object BubbleLikes {
    private fun prefs(context: Context) = context.getSharedPreferences("bubble_likes", Context.MODE_PRIVATE)
    fun liked(context: Context, id: String) = prefs(context).getBoolean(id, false)
    fun count(context: Context, post: Post): Int {
        val sample = post.id.startsWith("demo-") || SampleData.posts(context).any { it.id == post.id }
        val baseline = if (sample) 2 + Math.floorMod(post.id.hashCode(), 29) else 0
        return baseline + if (liked(context, post.id)) 1 else 0
    }
    fun toggle(context: Context, id: String) {
        prefs(context).edit().putBoolean(id, !liked(context, id)).apply()
    }
}
