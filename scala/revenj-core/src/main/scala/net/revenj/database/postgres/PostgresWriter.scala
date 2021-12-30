package net.revenj.database.postgres

import net.revenj.database.postgres.PostgresWriter.{EscapeBulk, NullCopy}
import net.revenj.database.postgres.converters.PostgresTuple
import org.postgresql.copy.CopyManager

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import org.postgresql.core.BaseConnection

class PostgresWriter extends PostgresBuffer with AutoCloseable {
  private var buffer = new Array[Char](64)
  val tmp: Array[Char] = new Array[Char](64)
  private var position = 0

  def close(): Unit = {
    position = 0
  }

  def reset(): Unit = {
    position = 0
  }

  def write(input: String): Unit = {
    val len = input.length
    if (position + len >= buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2 + len)
    }
    input.getChars(0, len, buffer, position)
    position += len
  }

  def write(c: Byte): Unit = {
    if (position == buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2)
    }
    buffer(position) = c.toChar
    position += 1
  }

  def write(c: Char): Unit = {
    if (position == buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2)
    }
    buffer(position) = c
    position += 1
  }

  def write(buf: Array[Char]): Unit = {
    if (position + buf.length >= buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2 + buf.length)
    }
    var i = 0
    while (i < buf.length) {
      buffer(position + i) = buf(i)
      i += 1
    }
    position += buf.length
  }

  def write(buf: Array[Char], len: Int): Unit = {
    if (position + len >= buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2 + len)
    }
    var i = 0
    while (i < len) {
      buffer(position + i) = buf(i)
      i += 1
    }
    position += len
  }

  def write(buf: Array[Char], off: Int, end: Int): Unit = {
    if (position + end >= buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2 + end)
    }
    var i = off
    while (i < end) {
      buffer(position + i - off) = buf(i)
      i += 1
    }
    position += end - off
  }

  def writeBuffer(len: Int): Unit = {
    if (position + len >= buffer.length) {
      buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2 + len)
    }
    var i = 0
    while (i < len) {
      buffer(position + i) = tmp(i)
      i += 1
    }
    position += len
  }

  override def toString: String = new String(buffer, 0, position)

  def tempBuffer: Array[Char] = tmp

  def initBuffer(): Unit = {
    reset()
  }

  def initBuffer(c: Char): Unit = {
    reset()
    write(c)
  }

  def addToBuffer(c: Char): Unit = {
    write(c)
  }

  def addToBuffer(buf: Array[Char]): Unit = {
    write(buf)
  }

  def addToBuffer(buf: Array[Char], len: Int): Unit = {
    write(buf, len)
  }

  def addToBuffer(buf: Array[Char], off: Int, end: Int): Unit = {
    write(buf, off, end)
  }

  def addToBuffer(input: String): Unit = {
    write(input)
  }

  def bufferToString(): String = {
    val result = toString
    position = 0
    result
  }

  def bulkInsert(connection: BaseConnection, table: String, data: Iterable[Array[PostgresTuple]]): Unit = {
    reset()
    data.foreach { tuples =>
      val first = tuples.head
      if (first != null && first != PostgresTuple.NULL) {
        first.insertRecord(this, "", EscapeBulk)
      } else {
        write(NullCopy)
      }
      var i = 1
      while (i < tuples.length) {
        val t = tuples(i)
        i += 1
        write('\t')
        if (t != null && t != PostgresTuple.NULL) {
          t.insertRecord(this, "", EscapeBulk)
        } else {
          write(NullCopy)
        }
      }
      write('\n')
    }
    if (position > 0) {
      val byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(buffer, 0, position))
      reset()
      val copy = new CopyManager(connection)
      val in = copy.copyIn(s"COPY $table FROM STDIN DELIMITER '\t'")
      try {
        in.writeToCopy(byteBuffer.array, 0, byteBuffer.limit)
        in.endCopy()
      } finally {
        // see to it that we do not leave the connection locked
        if (in.isActive) {
          in.cancelCopy()
        }
      }
    }
  }
}

object PostgresWriter {
  def create(): PostgresWriter = {
    new PostgresWriter
  }

  private val EscapeBulk = Some((sw, c) => PostgresTuple.escapeBulkCopy(sw, c))

  private val NullCopy = Array('\\', 'N')

  def writeSimpleUriList(sb: StringBuilder, uris: Array[String]): Unit = {
    sb.append('\'')
    var uri = uris(0)
    var ind = uri.indexOf('\'')
    if (ind == -1) {
      sb.append(uri)
    } else {
      var i = 0
      while (i < uri.length) {
        val c = uri.charAt(i)
        if (c == '\'') {
          sb.append("''")
        } else {
          sb.append(c)
        }
        i += 1
      }
    }
    var x = 1
    while (x < uris.length) {
      uri = uris(x)
      sb.append("','")
      ind = uri.indexOf('\'')
      if (ind == -1) {
        sb.append(uri)
      } else {
        var i = 0
        while (i < uri.length) {
          val c = uri.charAt(i)
          if (c == '\'') {
            sb.append("''")
          } else {
            sb.append(c)
          }
          i += 1
        }
      }
      x += 1
    }
    sb.append('\'')
  }

  def writeSimpleUri(sb: StringBuilder, uri: String): Unit = {
    sb.append('\'')
    val ind: Int = uri.indexOf('\'')
    if (ind == -1) {
      sb.append(uri)
    } else {
      var i = 0
      while (i < uri.length) {
        val c = uri.charAt(i)
        if (c == '\'') {
          sb.append("''")
        } else {
          sb.append(c)
        }
        i += 1
      }
    }
    sb.append('\'')
  }

  private def findEscapedChar(input: String): Int = {
    var i = 0
    var escapeAt = -1
    while (escapeAt == -1 && i < input.length) {
      val c = input.charAt(i)
      if (c == '\\' || c == '/' || c == '\'') {
        escapeAt = i
      }
      i += 1
    }
    escapeAt
  }

  def writeCompositeUriList(sb: StringBuilder, uris: Array[String]): Unit = {
    sb.append("('")
    var uri = uris(0)
    var i = 0
    var ind = findEscapedChar(uri)
    if (ind == -1) {
      sb.append(uri)
    } else {
      while (i < uri.length) {
        val c = uri.charAt(i)
        if (c == '\\') {
          i += 1
          sb.append(uri.charAt(i))
        } else if (c == '/') {
          sb.append("','")
        } else if (c == '\'') {
          sb.append("''")
        } else {
          sb.append(c)
        }
        i += 1
      }
    }
    var x = 1
    while (x < uris.length) {
      sb.append("'),('")
      uri = uris(x)
      ind = findEscapedChar(uri)
      if (ind == -1) {
        sb.append(uri)
      } else {
        i = 0
        while (i < uri.length) {
          val c = uri.charAt(i)
          if (c == '\\') {
            i += 1
            sb.append(uri.charAt(i))
          } else if (c == '/') {
            sb.append("','")
          } else if (c == '\'') {
            sb.append("''")
          } else {
            sb.append(c)
          }
          i += 1
        }
      }
      x += 1
    }
    sb.append("')")
  }

  def writeCompositeUri(sb: StringBuilder, uri: String): Unit = {
    sb.append("('")
    var i = 0
    val ind: Int = findEscapedChar(uri)
    if (ind == -1) {
      sb.append(uri)
    } else {
      while (i < uri.length) {
        val c: Char = uri.charAt(i)
        if (c == '\\') {
          i += 1
          sb.append(uri.charAt(i))
        } else if (c == '/') {
          sb.append("','")
        } else if (c == '\'') {
          sb.append("''")
        } else {
          sb.append(c)
        }
        i += 1
      }
    }
    sb.append("')")
  }

}