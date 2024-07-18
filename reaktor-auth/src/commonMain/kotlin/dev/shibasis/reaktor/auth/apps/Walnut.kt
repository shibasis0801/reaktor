package dev.shibasis.reaktor.auth.apps



enum class WalnutRole {
    SUPER_ADMIN,
    ADMIN,
    STAFF,
    TEACHER,
    PARENT;
    override fun toString(): String {
        return name.lowercase()
    }
}

enum class WalnutPermission {
    ADMISSIONS_FILL,
    ADMISSIONS_VIEW,
    ADMISSIONS_ADMIT,
    ATTENDANCE_MARK,
    ATTENDANCE_VIEW;

    override fun toString(): String {
        return name.lowercase()
    }
}

object Walnut {
    internal val walnutPermissions = hashMapOf<WalnutRole, List<WalnutPermission>>()
    init {
        walnutPermissions[WalnutRole.SUPER_ADMIN] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,
            WalnutPermission.ADMISSIONS_ADMIT,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.ADMIN] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,
            WalnutPermission.ADMISSIONS_ADMIT,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.STAFF] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.TEACHER] = listOf(
            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.PARENT] = listOf(
            WalnutPermission.ATTENDANCE_VIEW,
            WalnutPermission.ADMISSIONS_FILL,
        )
    }
}


val WalnutRole.permissions: List<WalnutPermission>
    get() = Walnut.walnutPermissions[this] ?: emptyList()