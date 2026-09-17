package com.absolutex.source.libarchive

import java.io.IOException

/** Reader boundary: these failures must not be presented as recovered/missing pages. */
sealed class ArchivePasswordException(message: String) : IOException(message)

class PasswordRequiredException(message: String) : ArchivePasswordException(message)

/** libarchive rejected the password; other data/CRC failures remain ordinary IO failures. */
class WrongPasswordException(message: String) : ArchivePasswordException(message)

/** The format or this build's crypto backend cannot decrypt the archive. Retrying won't help. */
class UnsupportedEncryptionException(message: String) : ArchivePasswordException(message)
