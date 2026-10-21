package com.brightpath.channel.service;

import com.brightpath.channel.domain.FileRef;
import com.brightpath.channel.repository.FileRefRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Local-disk file storage with metadata persisted in Postgres.
 * Uploads are streamed to a UUID-named file; the original filename is kept
 * only as metadata and validated before any download.
 */
@Singleton
public class FileStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorageService.class);

    /** File kinds that are never accepted, regardless of category allowlist. */
    private static final Set<String> BLOCKED_EXTENSIONS =
            Set.of("exe", "bat", "cmd", "com", "scr", "msi", "sh", "ps1", "jar", "js", "html", "htm", "svg", "php", "dll");

    private static final Set<String> COURSE_RESOURCE_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "zip");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private final Path baseDir;
    private final FileRefRepository fileRefs;
    private final SettingsService settings;

    public FileStorageService(@Value("${brightpath.upload-dir}") String uploadDir,
                              FileRefRepository fileRefs,
                              SettingsService settings) {
        this.fileRefs = fileRefs;
        this.settings = settings;
        this.baseDir = Path.of(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create upload directory " + baseDir, e);
        }
    }

    public FileRef store(CompletedFileUpload upload, SessionUser owner, String category,
                         Long courseId, Long assignmentId, Long submissionId,
                         Set<String> allowedExtensions) throws IOException {
        String originalName = sanitizeFilename(upload.getFilename());
        if (originalName.isBlank()) {
            throw AppException.badRequest("Please choose a file with a name.");
        }
        String extension = extensionOf(originalName);
        validateExtension(extension, category, allowedExtensions);
        long maxBytes = Math.min(owner.uploadMaxMb(), settings.uploadMaxMb()) * 1024L * 1024L;
        if (upload.getSize() > maxBytes) {
            throw AppException.badRequest("File exceeds the upload size limit of "
                    + Math.min(owner.uploadMaxMb(), settings.uploadMaxMb()) + " MB.");
        }

        String storedName = UUID.randomUUID().toString();
        Path target = baseDir.resolve(storedName).normalize();
        if (!target.startsWith(baseDir)) {
            throw AppException.forbidden("Invalid storage path.");
        }

        String sha256;
        long size;
        try (InputStream in = upload.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            Path tmp = target.resolveSibling(storedName + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    total += read;
                    if (total > maxBytes) {
                        Files.deleteIfExists(tmp);
                        throw AppException.badRequest("File exceeds the upload size limit.");
                    }
                }
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
            size = total;
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        FileRef ref = new FileRef();
        ref.setStoredName(storedName);
        ref.setOriginalName(originalName);
        ref.setMimeType(upload.getContentType().map(mediaType -> mediaType.toString()).orElse("application/octet-stream"));
        ref.setSizeBytes(size);
        ref.setSha256(sha256);
        ref.setCategory(category);
        ref.setOwnerId(owner.id());
        ref.setCourseId(courseId);
        ref.setAssignmentId(assignmentId);
        ref.setSubmissionId(submissionId);
        return fileRefs.save(ref);
    }

    /**
     * Imports a profile picture from a remote URL supplied by the owner
     * ("import from URL" alternative to picking a local file). The
     * fetched bytes are streamed straight to disk the same way an
     * uploaded avatar would be.
     */
    public FileRef storeAvatarFromUrl(SessionUser owner, String srcUrl) throws IOException {
        if (srcUrl == null || !(srcUrl.startsWith("http://") || srcUrl.startsWith("https://"))) {
            throw AppException.badRequest("Provide a valid http(s) image URL.");
        }
        String storedName = UUID.randomUUID().toString();
        Path target = baseDir.resolve(storedName).normalize();
        if (!target.startsWith(baseDir)) {
            throw AppException.forbidden("Invalid storage path.");
        }
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(srcUrl).toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(true);
        String contentType;
        long size;
        try (InputStream in = conn.getInputStream()) {
            size = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            contentType = conn.getContentType();
        } finally {
            conn.disconnect();
        }
        if (size > 5 * 1024L * 1024L) {
            Files.deleteIfExists(target);
            throw AppException.badRequest("Imported image exceeds the 5 MB limit.");
        }
        FileRef ref = new FileRef();
        ref.setStoredName(storedName);
        ref.setOriginalName("avatar-import");
        ref.setMimeType(contentType == null ? "application/octet-stream" : contentType);
        ref.setSizeBytes(size);
        ref.setSha256("");
        ref.setCategory(FileRef.CATEGORY_AVATAR);
        ref.setOwnerId(owner.id());
        return fileRefs.save(ref);
    }

    /** Persists already-fetched bytes (e.g. a synced directory photo) as an avatar FileRef. */
    public FileRef storeAvatarBytes(Long ownerId, byte[] bytes, String mimeType) throws IOException {
        String storedName = UUID.randomUUID().toString();
        Path target = baseDir.resolve(storedName).normalize();
        Files.write(target, bytes);
        FileRef ref = new FileRef();
        ref.setStoredName(storedName);
        ref.setOriginalName("directory-photo");
        ref.setMimeType(mimeType == null ? "application/octet-stream" : mimeType);
        ref.setSizeBytes(bytes.length);
        ref.setCategory(FileRef.CATEGORY_AVATAR);
        ref.setOwnerId(ownerId);
        return fileRefs.save(ref);
    }

    public void delete(FileRef ref) {
        try {
            Files.deleteIfExists(resolveStoredPath(ref.getStoredName()));
        } catch (IOException e) {
            LOG.warn("Could not delete stored file {}: {}", ref.getStoredName(), e.getMessage());
        }
    }

    /**
     * Resolves a stored file through the canonicalised base directory. Any path
     * that escapes the base (or does not exist) is rejected before it reaches
     * the filesystem stream.
     */
    public Path resolveStoredPath(String storedName) {
        if (storedName == null || storedName.contains("..") || storedName.contains("/") || storedName.contains("\\")) {
            throw AppException.notFound("File not found.");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(storedName).normalize();
        if (!resolved.startsWith(base) || !Files.isRegularFile(resolved)) {
            throw AppException.notFound("File not found.");
        }
        return resolved;
    }

    private void validateExtension(String extension, String category, Set<String> allowed) {
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw AppException.badRequest("Files of type ." + extension + " are not accepted.");
        }
        switch (category) {
            case FileRef.CATEGORY_SUBMISSION -> {
                if (allowed == null || !allowed.contains(extension)) {
                    throw AppException.badRequest("File type ." + extension + " is not allowed for this assignment. Allowed: "
                            + String.join(", ", allowed == null ? Set.of() : allowed));
                }
            }
            case FileRef.CATEGORY_COURSE_RESOURCE -> {
                if (!COURSE_RESOURCE_EXTENSIONS.contains(extension)) {
                    throw AppException.badRequest("Course resources must be a document or archive (pdf, docx, xlsx, pptx, zip, ...).");
                }
            }
            case FileRef.CATEGORY_IMPORT -> {
                if (!"csv".equals(extension)) {
                    throw AppException.badRequest("Imports must be CSV files.");
                }
            }
            case FileRef.CATEGORY_AVATAR -> {
                if (!IMAGE_EXTENSIONS.contains(extension)) {
                    throw AppException.badRequest("Avatars must be png, jpg, jpeg, gif or webp images.");
                }
            }
            default -> throw AppException.badRequest("Unknown file category.");
        }
    }

    private static String sanitizeFilename(String name) {
        if (name == null) {
            return "";
        }
        String base = name.replace("\\", "/");
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return base.replaceAll("[\\r\\n\\x00]", "").trim();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
