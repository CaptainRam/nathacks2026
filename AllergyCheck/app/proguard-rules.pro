# Scan history stores an AnalysisReport as a Java-serialised blob (see ScanHistoryStore), and Java
# serialisation writes class and field names into the stream. Today the release build's
# `optimization { packageScope = [androidx.**, kotlin.**, kotlinx.**] }` keeps app classes out of
# obfuscation entirely, so these rules change nothing as things stand. They are here so that
# widening packageScope later cannot silently make existing history rows unreadable.
-keep class com.example.allergycheck.analysis.** { *; }

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
