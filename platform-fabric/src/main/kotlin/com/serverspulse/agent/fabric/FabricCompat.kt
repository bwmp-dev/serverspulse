package com.serverspulse.agent.fabric

import com.mojang.brigadier.CommandDispatcher
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Supplier

internal object FabricCompat {

    fun invokeNoArg(target: Any, vararg methodNames: String): Any? {
        for (methodName in methodNames) {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: continue

            try {
                method.isAccessible = true
                return method.invoke(target)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun invokeWithArgs(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = findMethod(target, methodName, args) ?: return null

        return try {
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun invokeIfPresent(target: Any, methodName: String, vararg args: Any?): Boolean {
        val method = findMethod(target, methodName, args) ?: return false

        return try {
            method.isAccessible = true
            method.invoke(target, *args)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readField(target: Any, vararg fieldNames: String): Any? {
        for (fieldName in fieldNames) {
            var current: Class<*>? = target.javaClass
            while (current != null) {
                val field = current.declaredFields.firstOrNull { it.name == fieldName }
                if (field != null) {
                    try {
                        field.isAccessible = true
                        return field.get(target)
                    } catch (_: Throwable) {
                    }
                }
                current = current.superclass
            }
        }
        return null
    }

    fun invokeBoolean(target: Any, methodName: String, arg: Int): Boolean? {
        val result = invokeWithArgs(target, methodName, arg) ?: return null
        return result as? Boolean
    }

    @Suppress("UNCHECKED_CAST")
    fun findCommandDispatcher(server: Any): CommandDispatcher<Any>? {
        val commandManager = invokeNoArg(server, "getCommandManager")
            ?: readField(server, "commandManager")

        if (commandManager != null) {
            extractDispatcher(commandManager)?.let { return it }
        }

        for (fieldValue in readAllFields(server)) {
            extractDispatcher(fieldValue)?.let { return it }
        }

        return null
    }

    fun registerEventCallback(
        ownerClassName: String,
        fieldName: String,
        callbackClassName: String,
        handler: (Array<out Any?>) -> Unit
    ): Boolean {
        return try {
            val ownerClass = Class.forName(ownerClassName)
            val callbackClass = Class.forName(callbackClassName)
            val event = ownerClass.getField(fieldName).get(null) ?: return false

            val registerMethod = event.javaClass.methods.firstOrNull {
                it.name == "register" && it.parameterCount == 1
            } ?: return false

            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "toString" -> return@newProxyInstance "ServersPulseEventCallback"
                        "hashCode" -> return@newProxyInstance System.identityHashCode(this)
                        "equals" -> return@newProxyInstance false
                    }
                }

                handler(args ?: emptyArray())
                null
            }

            registerMethod.invoke(event, callback)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun currentServerFromLoader(): Any? {
        return try {
            val loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader")
            val loader = loaderClass.getMethod("getInstance").invoke(null)
            loaderClass.getMethod("getGameInstance").invoke(loader)
        } catch (_: Throwable) {
            null
        }
    }

    fun sendFeedback(source: Any, message: String) {
        val text = createText(message) ?: return

        val sendFeedbackMethods = source.javaClass.methods.filter {
            it.name == "sendFeedback" && it.parameterCount == 2
        }

        for (method in sendFeedbackMethods) {
            val argType = method.parameterTypes[0]
            try {
                when {
                    Supplier::class.java.isAssignableFrom(argType) -> {
                        method.invoke(source, Supplier { text }, false)
                        return
                    }

                    argType.name == "net.minecraft.text.Text" || argType.isInstance(text) -> {
                        method.invoke(source, text, false)
                        return
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val sendMessageMethods = source.javaClass.methods.filter { it.name == "sendMessage" }
        for (method in sendMessageMethods) {
            try {
                when {
                    method.parameterCount == 2 && method.parameterTypes[1] == UUID::class.java -> {
                        method.invoke(source, text, UUID(0L, 0L))
                        return
                    }

                    method.parameterCount == 1 -> {
                        method.invoke(source, text)
                        return
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun createText(message: String): Any? {
        return try {
            val textClass = Class.forName("net.minecraft.text.Text")

            try {
                val literal = textClass.getMethod("literal", String::class.java)
                literal.invoke(null, message)
            } catch (_: Throwable) {
                try {
                    val of = textClass.getMethod("of", String::class.java)
                    of.invoke(null, message)
                } catch (_: Throwable) {
                    val literalTextClass = Class.forName("net.minecraft.text.LiteralText")
                    literalTextClass.getConstructor(String::class.java).newInstance(message)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractDispatcher(target: Any): CommandDispatcher<Any>? {
        if (target is CommandDispatcher<*>) {
            return target as CommandDispatcher<Any>
        }

        val namedDispatcher = invokeNoArg(target, "getDispatcher")
        if (namedDispatcher is CommandDispatcher<*>) {
            return namedDispatcher as CommandDispatcher<Any>
        }

        val methodDispatcher = target.javaClass.methods.firstNotNullOfOrNull { method ->
            if (method.parameterCount != 0) return@firstNotNullOfOrNull null
            if (!CommandDispatcher::class.java.isAssignableFrom(method.returnType)) return@firstNotNullOfOrNull null

            try {
                method.isAccessible = true
                method.invoke(target) as? CommandDispatcher<Any>
            } catch (_: Throwable) {
                null
            }
        }
        if (methodDispatcher != null) {
            return methodDispatcher
        }

        for (fieldValue in readAllFields(target)) {
            if (fieldValue is CommandDispatcher<*>) {
                return fieldValue as CommandDispatcher<Any>
            }
        }

        return null
    }

    private fun readAllFields(target: Any): Sequence<Any> = sequence {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(target)
                    if (value != null) {
                        yield(value)
                    }
                } catch (_: Throwable) {
                }
            }
            current = current.superclass
        }
    }

    private fun matches(method: Method, args: Array<out Any?>): Boolean {
        if (method.parameterCount != args.size) return false
        return method.parameterTypes.indices.all { index ->
            isParameterCompatible(method.parameterTypes[index], args[index])
        }
    }

    private fun findMethod(target: Any, methodName: String, args: Array<out Any?>): Method? {
        return target.javaClass.methods.firstOrNull {
            it.name == methodName && matches(it, args)
        }
    }

    private fun isParameterCompatible(parameterType: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameterType.isPrimitive

        if (!parameterType.isPrimitive) {
            return parameterType.isAssignableFrom(value.javaClass)
        }

        return when (parameterType) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Byte.TYPE -> value is Byte
            java.lang.Short.TYPE -> value is Short
            java.lang.Character.TYPE -> value is Char
            java.lang.Integer.TYPE -> value is Int
            java.lang.Long.TYPE -> value is Long
            java.lang.Float.TYPE -> value is Float
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }
}
