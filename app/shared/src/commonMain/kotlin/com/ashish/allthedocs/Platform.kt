package com.ashish.allthedocs

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform