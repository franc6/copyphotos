# No library here loads its own classes by name on Android, so R8 can drop everything unused:
# - smbj loads its Kerberos (SPNEGO) authenticator with Class.forName only off Android
#   (SmbConfig.getDefaultAuthenticators checks for android.os.Build first) and adds NTLM directly.
# - smbj's BCSecurityProvider calls BouncyCastle's digests and ciphers directly, not through JCA.
# - Enums used through EnumSet keep values() via the default proguard-android-optimize.txt rules.
# Kerberos/JGSS and javax.el are referenced but never used on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
# dcerpc reports a malformed reply with java.rmi.UnmarshalException, which Android doesn't have;
# SmbFileSystem.listShares turns the resulting NoClassDefFoundError into a DestinationError.
-dontwarn java.rmi.UnmarshalException
