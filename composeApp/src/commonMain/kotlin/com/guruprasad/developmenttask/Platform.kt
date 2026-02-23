package com.guruprasad.developmenttask

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform