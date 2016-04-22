package scripts

import com.twitter.scrooge.Compiler

import scala.io.Source

import java.io.{ File, FileOutputStream, IOException }
import java.nio.file.{ Files, SimpleFileVisitor, FileVisitResult, Path, Paths }
import java.nio.file.attribute.{ BasicFileAttributes, FileTime }
import java.util.jar.{ JarEntry, JarFile, JarOutputStream }

object FinalJarCreator {
  val gm = """(\S+) -> (\S+)""".r

  def apply(dest: String, owned: Set[String], genFileMap: String, scroogeDir: String) {
    val genmap = Source.fromFile(genFileMap)
      .getLines
      .foldLeft(Map.empty[String, Set[String]]) { case (m, gm(thrift, gen)) =>
        m.+((thrift, m.getOrElse(thrift, Set.empty[String]) + gen))
      }
    val shouldMove =
      owned.foldLeft(Set.empty[String]) { (s, n) =>
        genmap.get(n).fold(s) { s ++ _ }
      }.map { Paths.get(_).normalize }
    val jar = new JarOutputStream(new FileOutputStream(dest))
    Files.walkFileTree(
      Paths.get(scroogeDir),
      FinalJarCreator(scroogeDir, jar, shouldMove)
    )
    jar.close()
  }
}
case class FinalJarCreator(_baseDir: String, jar: JarOutputStream, shouldMove: Set[Path]) extends SimpleFileVisitor[Path] {
  val baseDir = Paths.get(_baseDir).normalize

  // We return the path of the file to add to the jar
  def shouldVisitFile(file: Path): Option[Path] =
    if (shouldMove.contains(file)) Some(baseDir.relativize(file))
    else None

  override def visitFile(file: Path, attr: BasicFileAttributes) = {
    shouldVisitFile(file).foreach { _file =>
      val entry = new JarEntry(_file.toString)
      entry.setTime(198001010000L)
      jar.putNextEntry(entry)
      Files.copy(file, jar)
    }
    FileVisitResult.CONTINUE
  }
}

//TODO deal with errors etc
object DeleteRecursively extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attr: BasicFileAttributes) = {
    Files.delete(file)
    FileVisitResult.CONTINUE
  }

  override def postVisitDirectory(dir: Path, e: IOException) = {
    if (e != null) throw e
    Files.delete(dir)
    FileVisitResult.CONTINUE
  }
}

case class ForeachFile(f: Path => Unit) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attr: BasicFileAttributes) = {
    f(file)
    FileVisitResult.CONTINUE
  }
}

//TODO add logging?
object ScroogeGenerator {
  def deleteDir(path: String) {
    Files.walkFileTree(Paths.get(path), DeleteRecursively)
  }

  def extractJarTo(_jar: String, _dest: String) {
    val jar = new JarFile(_jar)
    val enumEntries = jar.entries()
    while (enumEntries.hasMoreElements()) {
      val file = enumEntries.nextElement().asInstanceOf[JarEntry]
      val f = new File(_dest, file.getName())
      if (file.isDirectory()) f.mkdir()
      else {
        val is = jar.getInputStream(file)
        val fos = new FileOutputStream(f)
        //TODO we can proably make this faster...
        while (is.available() > 0) fos.write(is.read())
        fos.close()
        is.close()
      }
    }
  }

  def main(args: Array[String]) {
    if (args.length < 4) sys.error("Need to ensure enough arguments! " +
      "Required 3 arguments: onlyTransitiveThriftSrcs immediateThriftSrcs " +
      "jarOutput remoteJarsFile. Received: " + args.mkString(","))

    val onlyTransitiveThriftSrcsFile = args(0)
    val immediateThriftSrcsFile = args(1)
    val jarOutput = args(2)
    val remoteJarsFile = args(3)

    val tmp = Paths.get(Option(System.getenv("TMPDIR")).getOrElse("/tmp"))
    val scroogeOutput = Files.createTempDirectory(tmp, "scrooge").toString

    // These are all of the files to include when generating scrooge
    // Should not include anything in immediateThriftSrcs
    val onlyTransitiveThriftSrcs =
      Source.fromFile(onlyTransitiveThriftSrcsFile).getLines.toSet

    // These are the files whose output we want
    val immediateThriftSrcs =
      Source.fromFile(immediateThriftSrcsFile).getLines.toSet

    val intersect = onlyTransitiveThriftSrcs.intersect(immediateThriftSrcs)
    if (intersect.nonEmpty)
      sys.error("onlyTransitiveThriftSrcs and immediateThriftSrcs should " +
        s"have not intersection, found: ${intersect.mkString(",")}")

    val genFileMap = s"$scroogeOutput/gen-file-map.txt"

    val scrooge = new Compiler
    immediateThriftSrcs.foreach { scrooge.thriftFiles += _ }
    onlyTransitiveThriftSrcs.foreach { scrooge.includePaths += _ }

    //TODO WE NEED TO TEST THIS!!
    val tmps =
      Source.fromFile(remoteJarsFile).getLines.toSet
        .map { jar: String =>
          val _tmp = Files.createTempDirectory(tmp, "jar")
          extractJarTo(jar, _tmp.toString)
          Files.walkFileTree(_tmp, ForeachFile { scrooge.includePaths += _.toString })
          _tmp
        }

    var dirsToDelete =
      List(
        scroogeOutput,
        onlyTransitiveThriftSrcsFile,
        immediateThriftSrcsFile,
        remoteJarsFile
      ).map(Paths.get(_)) ++ tmps

    scrooge.destFolder = scroogeOutput
    scrooge.fileMapPath = Some(genFileMap)
    scrooge.run()

    FinalJarCreator(jarOutput, immediateThriftSrcs, genFileMap, scroogeOutput)

    // Clean it out to be idempotent
    deleteDir(scroogeOutput)
    dirsToDelete.foreach { Files.delete(_) }
  }
}

/**

This is what currently exists, and what we want to transfer over

What we can't get around is making the input files available (to avoid length issues)
- file 1: [f.path for f in transitive_thrift_srcs]
- file 2: [f.path for f in immediate_thrift_srcs]
- invoke it with those paths

  cmd = """
rm -rf {out}_tmp
set -e
{java} -classpath "{jars}" com.twitter.scrooge.Main -d {out}_tmp --gen-file-map {gen_file_map} $@
find {out}_tmp -exec touch -t 198001010000 {{}} \;
touch -t 198001010000 {manifest}
{jar} cmf {manifest} {out} -C {out}_tmp .
rm -rf {out}_tmp
""".format(
    java=ctx.file._java.path,
    out=ctx.outputs.srcjar_polluted.path,
    manifest=ctx.outputs.manifest.path,
    jar=ctx.file._jar.path,
    jars=":".join([f.path for f in cjars]),
    gen_file_map=ctx.outputs.gen_file_map.path,
  )


ctx.action(
    inputs=list(transitive_thrift_srcs) +
        list(transitive_owned_srcs) +
        cjars +
        ctx.files._jdk +
        ctx.files._scalasdk +
        [ctx.outputs.manifest, ctx.file._jar],
    outputs=[ctx.outputs.srcjar_polluted, ctx.outputs.gen_file_map],
    command=cmd,
    progress_message="scrooge generation %s" % ctx.label,
    # Since we have access to the graph, we don't have to muck with the
    # thrift_library tar, we can just use the sources directly
    arguments=[f.path for f in transitive_thrift_srcs],
  )

  pluck_cmd="""
rm -rf {out}_tmp_polluted
rm -rf {out}_tmp
set -e
mkdir {out}_tmp_polluted
mkdir {out}_tmp
unzip -o {polluted} -d {out}_tmp_polluted 2>/dev/null
touch -t 198001010000 {manifest}
touch {out}_tmp_polluted/gen_pluck_cmd
{pluck} {jar} {manifest} {out} {out}_tmp_polluted/gen_pluck_cmd {out}_tmp_polluted {genmap} {include_base} {pluck_base} $@
bash {out}_tmp_polluted/gen_pluck_cmd
rm -rf {out}_tmp_polluted
rm -rf {out}_tmp
""".format(
    pluck = ctx.executable._pluck_scrooge.path,
    polluted = ctx.outputs.srcjar_polluted.path,
    genmap = ctx.outputs.gen_file_map.path,
    out = ctx.outputs.srcjar.path,
    manifest = ctx.outputs.manifest.path,
    jar = ctx.file._jar.path,
    include_base = ctx.outputs.srcjar_polluted.path + "_tmp",
    pluck_base = ctx.outputs.srcjar.path + "_tmp_polluted",
  )

  ctx.action(
    inputs = ctx.files._pluck_scrooge + [
      ctx.outputs.srcjar_polluted,
      ctx.outputs.gen_file_map,
      ctx.outputs.manifest,
      ctx.file._jar,
    ] +
    list(ctx.attr._pluck_scrooge.default_runfiles.files) +
    list(ctx.attr._pluck_scrooge.data_runfiles.files),
    outputs = [ctx.outputs.srcjar],
    command = pluck_cmd,
    progress_message = "plucking owned scrooge files %s" % ctx.label,
    arguments = [f.path for f in immediate_thrift_srcs],
  )
**/