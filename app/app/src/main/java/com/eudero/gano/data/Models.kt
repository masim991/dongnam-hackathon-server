package com.eudero.gano.data

data class Post(
    val id: String, val lat: Double, val lon: Double, val type: String /* person | warn | ask */,
    val road: String, val author: String, val text: String, val img: String, val created: Long,
    val local: Boolean = false, val synced: Boolean = true,
    val authorId: String = "", val visibility: String = "public"
) { val warn get() = type != "person" }

class Person(val id: String, val name: String, val relation: String /* me | friend | other */, val avatar: String, val bio: String)

class Node(val lat: Double, val lon: Double, val signal: Boolean)
