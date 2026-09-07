package moe.evil.hwhh.xposed.utils

class Memo<T : Any> {
    @Volatile
    private var value: T? = null

    fun orNull(compute: () -> T?): T? = value ?: synchronized(this) {
        value ?: compute()?.also { value = it }
    }

    fun orCatching(compute: () -> T): Result<T> =
        value?.let { Result.success(it) } ?: synchronized(this) {
            value?.let { Result.success(it) } ?: runCatching(compute).onSuccess { value = it }
        }

    fun clear() {
        value = null
    }
}
