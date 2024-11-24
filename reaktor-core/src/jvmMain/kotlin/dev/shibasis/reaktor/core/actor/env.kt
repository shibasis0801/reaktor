package dev.shibasis.reaktor.core.actor


fun getEnvString(key: String, defaultValue: String): String {
    val res = System.getenv(key);
    return res ?: defaultValue
}


fun getEnvInt(key: String, defaultValue: Int): Int {
    return getEnvString(key, "").run { ->
        if (equals("")) defaultValue else toInt()
    }
}