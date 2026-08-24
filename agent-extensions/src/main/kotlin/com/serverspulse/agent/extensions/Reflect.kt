package com.serverspulse.agent.extensions

import java.lang.reflect.Method

/**
 * The whole module talks to LuckPerms, Vault, LiteBans and Essentials through
 * these helpers rather than through compile-time dependencies.
 *
 * That is not a stylistic choice: adding four third-party artifacts to the
 * build so the agent can report a rank would make every build of every platform
 * jar depend on four more repositories staying online, to support plugins that
 * most servers do not have installed. Absence is the normal case here, and
 * absence has to be free.
 */
internal object Reflect {

    fun type(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

    fun method(owner: Class<*>?, name: String, vararg parameters: Class<*>): Method? {
        if (owner == null) return null
        return try {
            owner.getMethod(name, *parameters)
        } catch (_: Throwable) {
            null
        }
    }

    fun invoke(method: Method?, target: Any?, vararg arguments: Any?): Any? {
        if (method == null) return null
        return try {
            method.invoke(target, *arguments)
        } catch (_: Throwable) {
            null
        }
    }
}
