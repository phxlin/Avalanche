# Gson maps backup JSON onto these DTOs by field name; keep them intact when minifying.
-keep class com.avalanche.app.data.BackupFile { *; }
-keep class com.avalanche.app.data.BackupSettings { *; }
-keep class com.avalanche.app.data.DebtDto { *; }
-keep class com.avalanche.app.data.PaymentDto { *; }
-keepattributes Signature
