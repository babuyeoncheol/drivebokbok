# Vehicle SDK를 이름으로 찾아 부르므로(리플렉션) 난독화하면 안 된다.
-keep class ai.pleos.playground.** { *; }
-keep class kotlin.Metadata { *; }
