package com.carcast

/** Defaults that the car can reach: a non-RFC1918 address the Tesla browser does not block. */
object Config {
    /** CGNAT range (100.64/10). 240.x or 3.x are the fallbacks if a firmware update blocks this. */
    const val TUN_ADDRESS = "100.99.9.9"
    const val TUN_PREFIX = 32
    const val HTTP_PORT = 3333
}
