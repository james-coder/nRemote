# CI compile-only stubs

The real TI-Nspire NavNet libraries (`commproxy.jar`, `navnet.jar`,
`navnetcommproxy.jar`) ship with TI's software and cannot be redistributed.
These stubs declare the exact class/method **signatures** nRemote compiles
against, so CI can build `nRemote.jar` without them.

The stubs are compile-time only. The produced jar is a "no-libs" jar: it
contains only nRemote's classes and resolves the real TI classes at runtime,
when it is placed next to them per the README. Method **descriptors**
(parameter + return types) here are matched to the real jars so the compiled
bytecode links correctly against the real classes at runtime.
