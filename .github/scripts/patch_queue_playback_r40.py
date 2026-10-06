from pathlib import Path

path = Path('app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt')
text = path.read_text()
old = '''                .filter { method ->
                    !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterCount == 1 &&
'''
new = '''                .filter { method ->
                    method.declaringClass == serviceClass &&
                        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterCount == 1 &&
'''
if text.count(old) != 1:
    raise SystemExit(f'MusicService direct observer match count={text.count(old)}')
text = text.replace(old, new, 1)
path.write_text(text)
