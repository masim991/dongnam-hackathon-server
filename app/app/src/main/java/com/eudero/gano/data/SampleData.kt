package com.eudero.gano.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray

/** 샘플 사람(나 1·친구 4·다른 사람 5)과 샘플 글. assets/samples/ 의 같은 이름 파일을 바꾸면 그대로 반영. */
object SampleData {
    private var people: List<Person>? = null
    private var posts: List<Post>? = null

    fun people(c: Context): List<Person> = people ?: runCatching {
        val a = JSONArray(c.assets.open("samples/people.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map { val o = a.getJSONObject(it); Person(o.getString("id"), o.getString("name"), o.getString("relation"), o.getString("avatar"), o.optString("bio")) }
    }.getOrDefault(emptyList()).also { people = it }

    fun person(c: Context, id: String) = people(c).firstOrNull { it.id == id }

    fun posts(c: Context): List<Post> = posts ?: runCatching {
        val a = JSONArray(c.assets.open("samples/posts.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Post(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("type"), o.optString("road"), o.optString("author"),
                o.optString("text"), o.optString("img"), o.optLong("created"), false, true, o.optString("authorId"), o.optString("visibility", "public"))
        }
    }.getOrDefault(emptyList()).also { posts = it }

    fun avatar(c: Context, p: Person?): Bitmap? = p?.let { runCatching { c.assets.open(it.avatar).use { s -> BitmapFactory.decodeStream(s) } }.getOrNull() }
}
