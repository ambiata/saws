package com.ambiata.saws
package s3

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.amazonaws.AmazonServiceException
import com.ambiata.saws.core._
import com.ambiata.mundane.io._
import com.ambiata.mundane.control._

import java.io._

import scala.io.Source
import scala.collection.JavaConverters._
import scalaz._, Scalaz._
import scalaz.effect._
import scala.annotation.tailrec
import S3Path._
import scala.collection.JavaConversions._

object S3 {

  def getObject(path: FilePath): S3Action[S3Object] =
    getObject(bucket(path), key(path))

  def getObject(bucket: String, key: String): S3Action[S3Object] =
    S3Action(_.getObject(bucket, key)).onResult(_.prependErrorMessage(s"Could not get S3://${bucket}/${key}"))

  def getBytes(path: FilePath): S3Action[Array[Byte]] =
    getBytes(bucket(path), key(path))

  def getBytes(bucket: String, key: String): S3Action[Array[Byte]] =
    withStream(bucket, key, is => Streams.readBytes(is))

  def getString(path: FilePath): S3Action[String] =
    getString(bucket(path), key(path))

  def getStringWithEncoding(path: FilePath, encoding: String): S3Action[String] =
    getString(bucket(path), key(path))

  def getString(bucket: String, key: String, encoding: String = "UTF-8"): S3Action[String] =
    withStream(bucket, key, is => Streams.read(is, encoding))

  def withStreamUnsafe[A](path: FilePath, f: InputStream => A): S3Action[A] =
    withStreamUnsafe(bucket(path), key(path), f)

  def withStreamUnsafe[A](bucket: String, key: String, f: InputStream => A): S3Action[A] =
    getObject(bucket, key).map(o => f(o.getObjectContent))

  def withStream[A](path: FilePath, f: InputStream => ResultT[IO, A]): S3Action[A] =
    withStream(bucket(path), key(path), f)

  def withStream[A](bucket: String, key: String, f: InputStream => ResultT[IO, A]): S3Action[A] =
    getObject(bucket, key).flatMap(o => S3Action.fromResultT(f(o.getObjectContent)))

  def storeObject(path: FilePath, file: File): S3Action[File] =
    storeObject(bucket(path), key(path), file)

  def storeObjectMakeDirs(path: FilePath, file: File, mkdirs: Boolean): S3Action[File] =
    storeObject(bucket(path), key(path), file, mkdirs)

  def storeObject(bucket: String, key: String, file: File, mkdirs: Boolean = false): S3Action[File] =
    withStream(bucket, key, Files.writeStream(file.getAbsolutePath.toFilePath, _)).as(file)

  def readLines(path: FilePath): S3Action[List[String]] =
    readLines(bucket(path), key(path))

  def readLines(bucket: String, key: String): S3Action[List[String]] =
    withStream(bucket, key, Streams.read(_)).map(_.lines.toList)

  def downloadFile(path: FilePath): S3Action[File] =
    downloadFile(bucket(path), key(path))

  def downloadFileTo(path: FilePath, to: String): S3Action[File] =
    downloadFile(bucket(path), key(path), to)

  def downloadFile(bucket: String, key: String, to: String = "."): S3Action[File] =
    S3.withStream(bucket, key, Files.writeStream(to </> key, _)).as((to </> key).toFile)

  def putString(path: FilePath, data: String): S3Action[PutObjectResult] =
    putString(bucket(path), key(path), data)

  def putStringWithEncoding(path: FilePath, data: String, encoding: String): S3Action[PutObjectResult] =
    putString(bucket(path), key(path), data, encoding)

  def putString(bucket: String, key: String,  data: String, encoding: String = "UTF-8", metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[PutObjectResult] =
    putBytes(bucket, key, data.getBytes(encoding), metadata)

  def putBytes(path: FilePath,  data: Array[Byte]): S3Action[PutObjectResult] =
    putBytes(bucket(path), key(path), data)

  def putBytesWithMetadata(path: FilePath,  data: Array[Byte], metadata: ObjectMetadata): S3Action[PutObjectResult] =
    putBytes(bucket(path), key(path), data, metadata)

  def putBytes(bucket: String, key: String,  data: Array[Byte], metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[PutObjectResult] =
    putStream(bucket, key, new ByteArrayInputStream(data), metadata <| (_.setContentLength(data.length)))

  def putStream(path: FilePath,  stream: InputStream): S3Action[PutObjectResult] =
    putStream(bucket(path), key(path), stream)

  def putStreamWithMetadata(path: FilePath,  stream: InputStream, metadata: ObjectMetadata): S3Action[PutObjectResult] =
    putStream(bucket(path), key(path), stream, metadata)

  def putStream(bucket: String, key: String,  stream: InputStream, metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[PutObjectResult] =
    S3Action(_.putObject(bucket, key, stream, metadata))
             .onResult(_.prependErrorMessage(s"Could not put stream to S3://${bucket}/${key}"))

  def putFile(path: FilePath, file: File): S3Action[PutObjectResult] =
    putFile(bucket(path), key(path), file)

  def putFileWithMetatada(path: FilePath, file: File, metadata: ObjectMetadata): S3Action[PutObjectResult] =
    putFile(bucket(path), key(path), file, metadata)

  def putFile(bucket: String, key: String, file: File, metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[PutObjectResult] =
    S3Action(_.putObject(new PutObjectRequest(bucket, key, file).withMetadata(metadata)))
             .onResult(_.prependErrorMessage(s"Could not put file to S3://${bucket}/${key}"))

  /** If file is a directory, recursivly put all files and dirs under it on S3. If file is a file, put that file on S3. */
  def putFiles(path: FilePath, file: File): S3Action[List[PutObjectResult]] =
    putFiles(bucket(path), key(path), file, S3.ServerSideEncryption)

  def putFilesWithMetadata(path: FilePath, file: File, metadata: ObjectMetadata): S3Action[List[PutObjectResult]] =
    putFiles(bucket(path), key(path), file, metadata)

  def putFiles(bucket: String, prefix: String, file: File, metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[List[PutObjectResult]] =
    if(file.isDirectory)
      file.listFiles.toList.traverse(f => putFiles(bucket, prefix + "/" + f.getName, f, metadata)).map(_.flatten)
    else
      putFile(bucket, prefix, file, metadata).map(List(_))

  /** copy an object from s3 to s3, without downloading the object */
  // metadata disabled, since it copies the old objects metadata
  def copyFile(fromPath: FilePath, toPath: FilePath /*, metadata: ObjectMetadata = S3.ServerSideEncryption*/): S3Action[CopyObjectResult] =
    copyFile(bucket(fromPath), key(fromPath), bucket(toPath), key(toPath))

  def copyFile(fromBucket: String, fromKey: String, toBucket: String, toKey: String /*, metadata: ObjectMetadata = S3.ServerSideEncryption*/): S3Action[CopyObjectResult] =
    S3Action(_.copyObject(new CopyObjectRequest(fromBucket, fromKey, toBucket, toKey)))/*.withNewObjectMetadata(metadata) */
      .onResult(_.prependErrorMessage(s"Could not copy object from S3://${fromBucket}/${fromKey} to S3://${toBucket}/${toKey}"))

  def writeLines(path: FilePath, lines: Seq[String]): S3Action[PutObjectResult] =
    writeLines(bucket(path), key(path), lines)

  def writeLinesWithMetadata(path: FilePath, lines: Seq[String], metadata: ObjectMetadata): S3Action[PutObjectResult] =
    writeLines(bucket(path), key(path), lines, metadata)

  def writeLines(bucket: String, key: String, lines: Seq[String], metadata: ObjectMetadata = S3.ServerSideEncryption): S3Action[PutObjectResult] =
    putStream(bucket, key, new ByteArrayInputStream(lines.mkString("\n").getBytes), metadata) // TODO: Fix ram use

  def listSummary(path: FilePath): S3Action[List[S3ObjectSummary]] =
    listSummary(bucket(path), key(path))

  def listSummary(bucket: String, prefix: String = ""): S3Action[List[S3ObjectSummary]] =
    S3Action(client => {
      @tailrec
      def allObjects(prevObjectsListing : => ObjectListing, objects : List[S3ObjectSummary]): S3Action[List[S3ObjectSummary]] = {
        val previousListing = prevObjectsListing
        val previousObjects = previousListing.getObjectSummaries.asScala.toList
        if (previousListing.isTruncated())
          allObjects(client.listNextBatchOfObjects(previousListing), objects ++ previousObjects)
        else
          S3Action.ok(objects ++ previousObjects)
      }
      allObjects(client.listObjects(bucket, prefix), List())
    }).flatMap(x => x)

  def listKeys(path: FilePath): S3Action[List[String]] =
    listKeys(bucket(path), key(path))

  def listKeys(bucket: String, prefix: String = ""): S3Action[List[String]] =
    listSummary(bucket, prefix).map(_.map(_.getKey))

  def listBuckets: S3Action[List[Bucket]] =
    S3Action(_.listBuckets.toList).onResult(_.prependErrorMessage(s"Could access the buckets list"))

  def isS3Accessible: S3Action[Unit] =
    listBuckets.map(_ => ()).orElse(S3Action.fail("S3 is not accessible"))

  /** use this method to make sure that a prefix ends with a slash */
  def directory(prefix: String) = prefix + (if (prefix.endsWith("/")) "" else "/")

  def exists(path: FilePath): S3Action[Boolean] =
    exists(bucket(path), key(path))

  def exists(bucket: String, key: String): S3Action[Boolean] =
    S3Action((client: AmazonS3Client) => try {
        client.getObject(bucket, key)
        S3Action.ok(true)
      } catch {
        case ase: AmazonServiceException =>
          if (ase.getErrorCode == "NoSuchKey") S3Action.ok(false) else S3Action.exception(ase)
        case t: Throwable =>
          S3Action.exception(t)
      }).join

  def existsInBucket(bucket: String, filter: String => Boolean): S3Action[Boolean] =
    listSummary(bucket).map(_.exists(o => filter(o.getKey)))

  def deleteObject(path: FilePath): S3Action[Unit] =
    deleteObject(bucket(path), key(path))

  def deleteObject(bucket: String, key: String): S3Action[Unit] =
    S3Action(_.deleteObject(bucket, key))
             .onResult(_.prependErrorMessage(s"Could not delete S3://${bucket}/${key}"))

  def deleteObjects(bucket: String, f: String => Boolean = (s: String) => true): S3Action[Unit] =
    listSummary(bucket, "").flatMap(_.collect { case o if(f(o.getKey)) => deleteObject(bucket, o.getKey) }.sequence.map(_ => ()))

  def md5(path: FilePath): S3Action[String] =
    md5(bucket(path), key(path))

  def md5(bucket: String, key: String): S3Action[String] =
    S3Action(_.getObjectMetadata(bucket, key).getETag)
             .onResult(_.prependErrorMessage(s"Could not get md5 of S3://${bucket}/${key}"))

  def extractTarball(path: FilePath, local: File, stripLevels: Int): S3Action[File] =
    extractTarball(bucket(path), key(path), local, stripLevels)

  def extractTarball(bucket: String, key: String, local: File, stripLevels: Int): S3Action[File] =
    withStream(bucket, key, Archive.extractTarballStream(_, local.getAbsolutePath.toFilePath, stripLevels)).as(local)

  def extractTarballFlat(path: FilePath, local: File): S3Action[File] =
    extractTarballFlat(bucket(path), key(path), local)

  def extractTarballFlat(bucket: String, key: String, local: File): S3Action[File] =
    withStream(bucket, key, Archive.extractTarballStreamFlat(_, local.getAbsolutePath.toFilePath)).as(local)

  def extractGzip(path: FilePath, local: File): S3Action[File] =
    extractGzip(bucket(path), key(path), local)

  def extractGzip(bucket: String, key: String, local: File): S3Action[File] =
    withStream(bucket, key, is => Archive.extractGzipStream(is, local.getAbsolutePath.toFilePath)).as(local)

  def mirror(base: File, path: FilePath): S3Action[Unit] =
    mirror(base, bucket(path), key(path))

  def mirror(base: File, bucket: String, keybase: String): S3Action[Unit] = for {
    local <- S3Action.fromResultT { Directories.list(base.getAbsolutePath.toFilePath) }
    _     <- local.traverse({ source =>
      val destination = source.toFile.getAbsolutePath.replace(base.getAbsolutePath + "/", "")
      S3.putFile(bucket, s"${keybase}/${destination}", source.toFile)
    })
  } yield ()

  def deleteAll(path: FilePath): S3Action[Unit] =
    deleteAll(bucket(path), key(path))

  def deleteAll(bucket: String, keybase: String): S3Action[Unit] = for {
    all <- listSummary(bucket, keybase)
    _   <- all.traverse(obj => deleteObject(bucket, obj.getKey))
  } yield ()

  /** Object metadata that enables AES256 server-side encryption. */
  def ServerSideEncryption: ObjectMetadata = {
    val m = new ObjectMetadata
    m.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)
    m
  }
}
