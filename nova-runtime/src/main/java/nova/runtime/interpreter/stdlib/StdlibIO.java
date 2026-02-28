package nova.runtime.interpreter.stdlib;
import nova.runtime.*;
import nova.runtime.types.Environment;

import nova.runtime.interpreter.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * nova.io — 文件系统操作
 */
public final class StdlibIO {

    private StdlibIO() {}

    private static void checkFileIO(Interpreter interp) {
        if (!interp.getSecurityPolicy().isFileIOAllowed()) {
            throw NovaSecurityPolicy.denied("file I/O operations are not allowed");
        }
    }

    public static void register(Environment env, Interpreter interp) {
        env.defineVal("readFile", NovaNativeFunction.create("readFile", (path) -> {
            checkFileIO(interp);
            try {
                return NovaString.of(new String(Files.readAllBytes(Paths.get(path.asString())), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new NovaRuntimeException("readFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("writeFile", NovaNativeFunction.create("writeFile", (path, content) -> {
            checkFileIO(interp);
            try {
                Files.write(Paths.get(path.asString()), content.asString().getBytes(StandardCharsets.UTF_8));
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("writeFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("appendFile", NovaNativeFunction.create("appendFile", (path, content) -> {
            checkFileIO(interp);
            try {
                Files.write(Paths.get(path.asString()), content.asString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("appendFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("readLines", NovaNativeFunction.create("readLines", (path) -> {
            checkFileIO(interp);
            try {
                List<String> lines = Files.readAllLines(Paths.get(path.asString()), StandardCharsets.UTF_8);
                NovaList result = new NovaList();
                for (String line : lines) result.add(NovaString.of(line));
                return result;
            } catch (IOException e) {
                throw new NovaRuntimeException("readLines failed: " + e.getMessage());
            }
        }));

        env.defineVal("writeLines", NovaNativeFunction.create("writeLines", (path, lines) -> {
            checkFileIO(interp);
            try {
                NovaList list = (NovaList) lines;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(System.lineSeparator());
                    sb.append(list.get(i).asString());
                }
                Files.write(Paths.get(path.asString()), sb.toString().getBytes(StandardCharsets.UTF_8));
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("writeLines failed: " + e.getMessage());
            }
        }));

        // readBytes(path) → NovaList of Int (byte values)
        env.defineVal("readBytes", NovaNativeFunction.create("readBytes", (path) -> {
            checkFileIO(interp);
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(path.asString()));
                NovaList result = new NovaList();
                for (byte b : bytes) result.add(NovaInt.of(b & 0xFF));
                return result;
            } catch (IOException e) {
                throw new NovaRuntimeException("readBytes failed: " + e.getMessage());
            }
        }));

        // writeBytes(path, bytes) → write byte list to file
        env.defineVal("writeBytes", NovaNativeFunction.create("writeBytes", (path, bytesVal) -> {
            checkFileIO(interp);
            try {
                NovaList list = (NovaList) bytesVal;
                byte[] bytes = new byte[list.size()];
                for (int i = 0; i < list.size(); i++) bytes[i] = (byte) list.get(i).asInt();
                Files.write(Paths.get(path.asString()), bytes);
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("writeBytes failed: " + e.getMessage());
            }
        }));

        env.defineVal("fileExists", NovaNativeFunction.create("fileExists", (path) -> {
            checkFileIO(interp);
            return NovaBoolean.of(Files.exists(Paths.get(path.asString())));
        }));

        env.defineVal("deleteFile", NovaNativeFunction.create("deleteFile", (path) -> {
            checkFileIO(interp);
            try {
                Files.deleteIfExists(Paths.get(path.asString()));
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("deleteFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("copyFile", NovaNativeFunction.create("copyFile", (src, dst) -> {
            checkFileIO(interp);
            try {
                Files.copy(Paths.get(src.asString()), Paths.get(dst.asString()), StandardCopyOption.REPLACE_EXISTING);
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("copyFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("moveFile", NovaNativeFunction.create("moveFile", (src, dst) -> {
            checkFileIO(interp);
            try {
                Files.move(Paths.get(src.asString()), Paths.get(dst.asString()), StandardCopyOption.REPLACE_EXISTING);
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("moveFile failed: " + e.getMessage());
            }
        }));

        env.defineVal("listDir", NovaNativeFunction.create("listDir", (path) -> {
            checkFileIO(interp);
            try (Stream<Path> stream = Files.list(Paths.get(path.asString()))) {
                NovaList result = new NovaList();
                stream.forEach(p -> result.add(NovaString.of(p.toString())));
                return result;
            } catch (IOException e) {
                throw new NovaRuntimeException("listDir failed: " + e.getMessage());
            }
        }));

        env.defineVal("mkdir", NovaNativeFunction.create("mkdir", (path) -> {
            checkFileIO(interp);
            try {
                Files.createDirectory(Paths.get(path.asString()));
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("mkdir failed: " + e.getMessage());
            }
        }));

        env.defineVal("mkdirs", NovaNativeFunction.create("mkdirs", (path) -> {
            checkFileIO(interp);
            try {
                Files.createDirectories(Paths.get(path.asString()));
                return NovaNull.UNIT;
            } catch (IOException e) {
                throw new NovaRuntimeException("mkdirs failed: " + e.getMessage());
            }
        }));

        env.defineVal("isFile", NovaNativeFunction.create("isFile", (path) -> {
            checkFileIO(interp);
            return NovaBoolean.of(Files.isRegularFile(Paths.get(path.asString())));
        }));

        env.defineVal("isDir", NovaNativeFunction.create("isDir", (path) -> {
            checkFileIO(interp);
            return NovaBoolean.of(Files.isDirectory(Paths.get(path.asString())));
        }));

        env.defineVal("fileSize", NovaNativeFunction.create("fileSize", (path) -> {
            checkFileIO(interp);
            try {
                return NovaLong.of(Files.size(Paths.get(path.asString())));
            } catch (IOException e) {
                throw new NovaRuntimeException("fileSize failed: " + e.getMessage());
            }
        }));

        // 路径操作 — 纯字符串操作，不需要安全检查
        env.defineVal("pathJoin", new NovaNativeFunction("pathJoin", -1, (interpreter, args) -> {
            if (args.isEmpty()) return NovaString.of("");
            Path p = Paths.get(args.get(0).asString());
            for (int i = 1; i < args.size(); i++) p = p.resolve(args.get(i).asString());
            return NovaString.of(p.toString());
        }));

        env.defineVal("fileName", NovaNativeFunction.create("fileName", (path) -> {
            Path name = Paths.get(path.asString()).getFileName();
            return name != null ? NovaString.of(name.toString()) : NovaNull.NULL;
        }));

        env.defineVal("fileExtension", NovaNativeFunction.create("fileExtension", (path) -> {
            String name = Paths.get(path.asString()).getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? NovaString.of(name.substring(dot + 1)) : NovaString.of("");
        }));

        env.defineVal("parentDir", NovaNativeFunction.create("parentDir", (path) -> {
            Path parent = Paths.get(path.asString()).getParent();
            return parent != null ? NovaString.of(parent.toString()) : NovaNull.NULL;
        }));

        env.defineVal("absolutePath", NovaNativeFunction.create("absolutePath", (path) ->
            NovaString.of(Paths.get(path.asString()).toAbsolutePath().toString())));

        env.defineVal("currentDir", NovaNativeFunction.create("currentDir", () ->
            NovaString.of(System.getProperty("user.dir"))));

        env.defineVal("tempDir", NovaNativeFunction.create("tempDir", () ->
            NovaString.of(System.getProperty("java.io.tmpdir"))));

        env.defineVal("tempFile", new NovaNativeFunction("tempFile", -1, (interpreter, args) -> {
            checkFileIO(interp);
            try {
                String prefix = args.size() > 0 ? args.get(0).asString() : "nova";
                String suffix = args.size() > 1 ? args.get(1).asString() : ".tmp";
                Path tmp = Files.createTempFile(prefix, suffix);
                return NovaString.of(tmp.toString());
            } catch (IOException e) {
                throw new NovaRuntimeException("tempFile failed: " + e.getMessage());
            }
        }));
    }
}
