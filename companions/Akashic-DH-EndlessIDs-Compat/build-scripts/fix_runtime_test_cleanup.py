from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\tests\RuntimeActivationVerification.java")
s = p.read_text(encoding="utf-8")
s = s.replace(
    '            require(countCalls(untouched, true) == 0, "Hook appeared without EndlessIDs");\n\n            Path fakeEndlessIds',
    '            require(countCalls(untouched, true) == 0, "Hook appeared without EndlessIDs");\n            Launch.classLoader.close();\n\n            Path fakeEndlessIds'
)
s = s.replace(
    '            require(countCalls(patched, true) == 1, "Expected hook missing with EndlessIDs present");\n            Files.deleteIfExists(fakeEndlessIds);',
    '            require(countCalls(patched, true) == 1, "Expected hook missing with EndlessIDs present");\n            Launch.classLoader.close();\n            Launch.classLoader = previous;\n            Files.deleteIfExists(fakeEndlessIds);'
)
p.write_text(s, encoding="utf-8")
