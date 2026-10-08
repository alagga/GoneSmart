from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {text.count(old)}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt",
    """    fun method(\n        type: Class<*>,\n""",
    """    fun matchesDeclaredPresenterRuleAction(\n        method: Method,\n        presenterClass: Class<*>,\n        baseRuleClass: Class<*>\n    ): Boolean =\n        method.declaringClass == presenterClass &&\n            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&\n            method.parameterTypes.contentEquals(arrayOf(baseRuleClass)) &&\n            method.returnType == java.lang.Void.TYPE\n\n    fun method(\n        type: Class<*>,\n"""
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt",
    """        val presenterAddRule = r.method(\n            presenterClass,\n            listOf(\"P1\"),\n            \"Smart editor add-rule action\"\n        ) { method ->\n            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&\n                method.parameterCount == 1 &&\n                method.parameterTypes[0].isAssignableFrom(smartRuleClass)\n        }\n""",
    """        val presenterAddRule = r.method(\n            presenterClass,\n            listOf(\"P1\", \"Q1\"),\n            \"Smart editor add-rule action\"\n        ) { method ->\n            r.matchesDeclaredPresenterRuleAction(\n                method = method,\n                presenterClass = presenterClass,\n                baseRuleClass = baseRuleClass\n            )\n        }\n"""
)

replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt",
    """    private class FieldFixture {\n        @Suppress(\"unused\") private var wanted: String = \"x\"\n        @Suppress(\"unused\") private var other: Int = 1\n    }\n""",
    """    private class FieldFixture {\n        @Suppress(\"unused\") private var wanted: String = \"x\"\n        @Suppress(\"unused\") private var other: Int = 1\n    }\n\n    private open class RuleBase\n    private class RuleLeaf : RuleBase()\n\n    private open class PresenterParent {\n        @Suppress(\"unused\") fun S1(value: Any) = Unit\n    }\n\n    private class PresenterFixture : PresenterParent() {\n        @Suppress(\"unused\") fun Q1(value: RuleBase) = Unit\n    }\n"""
)

replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt",
    """    @Test\n    fun preferredFieldIsShapeChecked() {\n""",
    """    @Test\n    fun presenterRuleActionIgnoresInheritedBroadDistractors() {\n        val method = PlaylistBridgeReflectionResolver.method(\n            PresenterFixture::class.java,\n            listOf(\"P1\", \"Q1\"),\n            \"presenter rule action\"\n        ) { candidate ->\n            PlaylistBridgeReflectionResolver.matchesDeclaredPresenterRuleAction(\n                method = candidate,\n                presenterClass = PresenterFixture::class.java,\n                baseRuleClass = RuleBase::class.java\n            )\n        }\n\n        assertEquals(\"Q1\", method.name)\n        assertSame(PresenterFixture::class.java, method.declaringClass)\n        assertSame(RuleBase::class.java, method.parameterTypes.single())\n    }\n\n    @Test\n    fun preferredFieldIsShapeChecked() {\n"""
)

playbook = Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md")
text = playbook.read_text()
anchor = """For Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP can replace that listener later in the Smart editor lifecycle. Keep the accepted 4.2.0 `ds4.g2(boolean)` path exact. On 4.2.1, intercept the proven `as4$g.accept(...)` callback for GoneSmart's type chooser and retain the exact callback instance/payload as a one-shot native continuation; choosing Smart Playlist resumes that original continuation. This avoids inventing an upstream mapping while keeping ordinary native edit/disabled paths pass-through.\n"""
addition = anchor + "\nFor the Smart-editor add-rule action, resolve only a method declared directly by the resolved presenter whose single parameter is exactly the resolved Smart-rule base class and whose return type is `void`. Historical `P1` (4.2.0) and observed `Q1` (4.2.1) names are only fast paths. Broad assignability is intentionally rejected because 4.2.1 exposed false candidates such as inherited `equals(Object)` and `S1(...)`.\n"
if text.count(anchor) != 1:
    raise SystemExit("playbook anchor mismatch")
playbook.write_text(text.replace(anchor, addition, 1))

completion = Path("docs/GMMP_421_COMPLETION.md")
text = completion.read_text()
line = "- 8 October 2026: after a full device reboot, runtime build identity was proven as `57aff0f33885` on `fix/playlist-link-gmmp-4.2.1`. Playlist Link then failed deterministically during binding because the add-rule resolver admitted `as4.Q1(dt4)`, inherited `equals(Object)` and `S1(...)` under an overly broad assignability predicate. The repair now requires a directly-declared presenter method taking exactly the resolved Smart-rule base class and returning `void`; `P1`/`Q1` remain fast-path names only. Device re-acceptance remains pending.\n"
if line not in text:
    completion.write_text(text.rstrip() + "\n" + line)
