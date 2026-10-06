from pathlib import Path

path = Path("app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt")
text = path.read_text()

old_field = """    private val positionWriterObserver = NativeQueuePositionWriterObserver()\n"""
new_field = """    private val positionWriterObserver = NativeQueuePositionWriterObserver()\n    private val observedPositionCommands =\n        java.util.Collections.synchronizedSet(mutableSetOf<String>())\n    private val autoDjAccessorFailureLogged =\n        java.util.concurrent.atomic.AtomicBoolean(false)\n"""
if text.count(old_field) != 1:
    raise SystemExit(f"position observer field match count={text.count(old_field)}")
text = text.replace(old_field, new_field, 1)

old_method = '''    fun aroundNativePositionCommand(\n        service: Any?,\n        method: Method,\n        value: Int?,\n        proceed: () -> Any?\n    ): Any? = positionWriterObserver.aroundNaturalInvocation(\n        service = service,\n        method = method,\n        argument = value,\n        autoDj = nativeAutoDj?.get(),\n        proceed = proceed\n    )\n'''
new_method = '''    fun aroundNativePositionCommand(\n        service: Any?,\n        method: Method,\n        value: Int?,\n        proceed: () -> Any?\n    ): Any? {\n        val autoDj = nativeAutoDj?.get()\n            ?: service?.let(::resolveNativeAutoDjFromService)\n        if (service != null && value != null) {\n            val key = method.declaringClass.name + "." + method.name + "(int)"\n            if (observedPositionCommands.add(key)) {\n                Log.i(\n                    TAG,\n                    "QUEUE POSITION COMMAND OBSERVED | command=$key | arg=$value | " +\n                        "autoDj=" + if (autoDj != null) "ready" else "unresolved"\n                )\n            }\n        }\n        return positionWriterObserver.aroundNaturalInvocation(\n            service = service,\n            method = method,\n            argument = value,\n            autoDj = autoDj,\n            proceed = proceed\n        )\n    }\n\n    private fun resolveNativeAutoDjFromService(service: Any): Any? {\n        val resolution = NativeAutoDjAccessorResolver.resolve(service)\n        if (resolution != null) {\n            captureNativeAutoDj(resolution.instance)\n            Log.i(\n                TAG,\n                "QUEUE AUTO DJ CAPTURE | source=service-accessor | accessor=" +\n                    resolution.accessor.declaringClass.name + "." +\n                    resolution.accessor.name + "():" +\n                    resolution.accessor.returnType.name\n            )\n            return resolution.instance\n        }\n        if (autoDjAccessorFailureLogged.compareAndSet(false, true)) {\n            Log.w(\n                TAG,\n                "QUEUE AUTO DJ ACCESSOR UNRESOLVED | candidates=" +\n                    NativeAutoDjAccessorResolver.diagnosticShape(service.javaClass)\n            )\n        }\n        return null\n    }\n'''
if text.count(old_method) != 1:
    raise SystemExit(f"position observer method match count={text.count(old_method)}")
text = text.replace(old_method, new_method, 1)
path.write_text(text)
