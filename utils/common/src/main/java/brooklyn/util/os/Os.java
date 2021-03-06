package brooklyn.util.os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Urls;
import brooklyn.util.stream.Streams;
import brooklyn.util.text.Strings;

import com.google.common.annotations.Beta;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class Os {

    private static final Logger log = LoggerFactory.getLogger(Os.class);
    
    /** returns the best tmp dir to use; see {@link TmpDirFinder} for the logic
     * (and the explanation why this is needed!) */
    public static String tmp() {
        Maybe<String> tmp = tmpdir.get();
        if (tmp.isPresent()) return tmp.get();

        tmpdir.useWithWarning(System.getProperty("java.io.tmpdir"));
        return tmp.get();
    }
    
    private static TmpDirFinder tmpdir = new TmpDirFinder();
    
    /** utility for finding a usable (writable) tmp dir, preferring java.io.tmpdir
     * (unless it's weird, e.g. /private/tmp/xxx or /var/tmp/... as under OS X, and /tmp is valid),
     * falling back to ~/.tmp/ (and creating that) if the others are not usable
     * <p>
     * it is weird if /tmp is not writable, but it does happen, hence this check
     * <p>
     * note you can also set java system property {@value #BROOKLYN_OS_TMPDIR_PROPERTY} 
     * to force the use of a specific tmp space */
    public static class TmpDirFinder {
        /** can be set as a jvm system property to force a particular tmp dir; directory must exist with the right permissions */
        public static String BROOKLYN_OS_TMPDIR_PROPERTY = "brooklyn.os.tmpdir";
        
        private String tmpdir = null;
        private boolean isFallback = false;
        
        public Maybe<String> get() {
            if (isFallback())
                log.debug("TmpDirFinder: using fallback tmp directory "+tmpdir, new Throwable("Caller using fallback tmp dir"));
            if (isFound()) return Maybe.of(tmpdir);
            if (find()) return Maybe.of(tmpdir);
            return Maybe.absent(newFailure("TmpDirFinder: No valid tmp dir can be found"));
        }

        public boolean isFallback() {
            return isFallback;
        }
        
        public boolean useWithWarning(String dir) {
            if (tmpdir==null) {
                tmpdir = dir;
                isFallback = true;
                log.warn("Unable to find a valid tmp dir; will use "+dir+" but with caution! See (debug) messages marked TmpDirFinder for more information.");
                return true;
            }
            return false;
        }

        public boolean isFound() {
            return tmpdir!=null;
        }
        protected synchronized boolean find() {
            if (isFound()) return true;

            String customtmp = System.getProperty(BROOKLYN_OS_TMPDIR_PROPERTY);
            if (customtmp!=null) {
                if (checkAndSet(customtmp)) return true;
                log.warn("TmpDirFinder: Custom tmp directory '"+customtmp+"' in "+BROOKLYN_OS_TMPDIR_PROPERTY+" is not a valid tmp dir; ignoring");
            }
            
            String systmp = System.getProperty("java.io.tmpdir");
            boolean systmpWeird = (systmp.contains("/var/") || systmp.startsWith("/private"));
            if (!systmpWeird) if (checkAndSet(systmp)) return true;

            if (checkAndSet(File.separator+"tmp")) return true;
            if (systmpWeird) if (checkAndSet(systmp)) return true;
            
            try {
                String hometmp = mergePaths(home(), ".tmp");
                File hometmpF = new File(hometmp);
                hometmpF.mkdirs();
                if (checkAndSet(hometmp)) return true;
            } catch (Exception e) {
                log.debug("TmpDirFinder: Cannot create tmp dir in user's home dir: "+e);
            }
            
            return false;
        }
        
        protected boolean checkAndSet(String candidate) {
            if (!check(candidate)) return false;
            // seems okay
            tmpdir = candidate;
            log.debug("TmpDirFinder: Selected tmp dir '"+candidate+"' as the best tmp working space");
            return true;
        }
        
        protected boolean check(String candidate) {
            try {
                File f = new File(candidate);
                if (!f.exists()) {
                    log.debug("TmpDirFinder: Candidate tmp dir '"+candidate+"' does not exist");
                    return false;
                }
                if (!f.isDirectory()) {
                    log.debug("TmpDirFinder: Candidate tmp dir '"+candidate+"' is not a directory");
                    return false;
                }
                File f2 = new File(f, "brooklyn-tmp-check-"+Strings.makeRandomId(4));
                if (!f2.createNewFile()) {
                    log.debug("TmpDirFinder: Candidate tmp dir '"+candidate+"' cannot have files created inside it ("+f2+")");
                    return false;
                }
                if (!f2.delete()) {
                    log.debug("TmpDirFinder: Candidate tmp dir '"+candidate+"' cannot have files deleted inside it ("+f2+")");
                    return false;
                }
                
                return true;
            } catch (Exception e) {
                log.debug("TmpDirFinder: Candidate tmp dir '"+candidate+"' is not valid: "+e);
                return false;
            }
        }
        
        protected IllegalStateException newFailure(String message) {
            return new IllegalStateException(message);
        }
    }

    /** user name */
    public static String user() {
        return System.getProperty("user.name");
    }

    /** user's home directory */
    public static String home() {
        return System.getProperty("user.home");
    }

    /** merges paths using forward slash (unix way); see {@link Urls#mergePaths(String...)} */
    public static String mergePathsUnix(String ...items) {
        return Urls.mergePaths(items);
    }

    /** merges paths using the local file separator */
    public static String mergePaths(String ...items) {
        char separatorChar = File.separatorChar;
        StringBuilder result = new StringBuilder();
        for (String item: items) {
            if (item.isEmpty()) continue;
            if (result.length() > 0 && result.charAt(result.length()-1) != separatorChar) result.append(separatorChar);
            result.append(item);
        }
        return result.toString();
    }

    /** tries to delete a directory recursively;
     * use with care - see http://stackoverflow.com/questions/8320376/why-is-files-deletedirectorycontents-deprecated-in-guava.
     * @return true if there are no errors (but the directory may still exist if a file is created there in parallel),
     * false if there are errors (without reporting the errors)
     * <p>
     * NB: this method might change, based on Guava choosing to deprecate it;
     * also the return type might be modified in future to supply more information;
     * thus it is marked beta */
    @Beta
    public static boolean tryDeleteDirectory(File dir) {
        try {
            FileUtils.deleteDirectory(dir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * @see {@link #tryDeleteDirectory(File)}
     */
    @Beta
    public static boolean tryDeleteDirectory(String dir) {
        return tryDeleteDirectory(new File(dir));
    }

    private static class FileDeletionHook {
        public FileDeletionHook(File f, boolean recursively) {
            this.path = f;
            this.recursively = recursively;
        }
        final File path;
        final boolean recursively;
        
        public void run() throws IOException {
            if (path.exists()) {
                if (recursively && path.isDirectory()) {
                    FileUtils.deleteDirectory(path);
                } else {
                    path.delete();
                }
            }
        }
    }
    
    private static final Map<String,FileDeletionHook> deletions = new LinkedHashMap<String, Os.FileDeletionHook>();
    
    private static void addShutdownFileDeletionHook(String path, FileDeletionHook hook) {
        synchronized (deletions) {
            if (deletions.isEmpty()) {
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        synchronized (deletions) {
                            List<String> pathsToDelete = new ArrayList<String>(deletions.keySet());
                            Collections.sort(pathsToDelete, Strings.lengthComparator().reverse());
                            for (String path: pathsToDelete) {
                                try {
                                    deletions.remove(path).run();
                                } catch (Exception e) {
                                    log.warn("Unable to delete '"+path+"' on shutdown: "+e);
                                }
                            }
                        }
                    }
                });
            }
            FileDeletionHook oldHook = deletions.put(path, hook);
            if (oldHook!=null && oldHook.recursively)
                // prefer any hook which is recursive
                deletions.put(path, oldHook);
        }
    }

    /** deletes the given file or empty directory on exit
     * <p>
     * similar to {@link File#deleteOnExit()} except it is smart about trying to delete longer filenames first
     * (and the shutdown hook order does not use proprietary java hooks)
     * <p>
     * note this does not delete non-empty directories; see {@link #deleteOnExitRecursively(File)} for that */
    public static void deleteOnExit(File directoryToDeleteIfEmptyOrFile) {
        addShutdownFileDeletionHook(directoryToDeleteIfEmptyOrFile.getAbsolutePath(), new FileDeletionHook(directoryToDeleteIfEmptyOrFile, false));
    }

    /** deletes the given file or directory and, in the case of directories, any contents;
     * similar to apache commons FileUtils.cleanDirectoryOnExit but corrects a bug in that implementation
     * which causes it to fail if content is added to that directory after the hook is registered */
    public static void deleteOnExitRecursively(File directoryToCleanOrFile) {
        addShutdownFileDeletionHook(directoryToCleanOrFile.getAbsolutePath(), new FileDeletionHook(directoryToCleanOrFile, true));
    }

    /** causes empty directories from subsubdir up to and including dir to be deleted on exit;
     * useful e.g. if we create  /tmp/brooklyn-test/foo/test1/  and someone else might create
     * /tmp/brooklyn-test/foo/test2/   and we'd like the last tear-down to result in /tmp/brooklyn-test  being deleted!
     * <p> 
     * returns number of directories queued for deletion so caller can check for errors if desired;
     * if dir is not an ancestor of subsubdir this logs a warning but does not throw  */
    public static int deleteOnExitEmptyParentsUpTo(File subsubDirOrFile, File dir) {
        if (subsubDirOrFile==null || dir==null) 
            return 0;
        
        List<File> dirsToDelete = new ArrayList<File>();
        File d = subsubDirOrFile;
        do {
            dirsToDelete.add(d);
            if (d.equals(dir)) break;
            d = d.getParentFile();
        } while (d!=null);
        
        if (d==null) {
            log.warn("File "+subsubDirOrFile+" has no ancestor "+dir+": will not attempt to clean up with ancestors on exit");
            // dir is not an ancestor if subsubdir
            return 0;
        }
        
        for (File f: dirsToDelete)
            deleteOnExit(f);
        
        return dirsToDelete.size();
    }

    /** like {@link #deleteOnExitRecursively(File)} followed by {@link #deleteOnExitEmptyParentsUpTo(File, File)} */
    public static void deleteOnExitRecursivelyAndEmptyParentsUpTo(File directoryToCleanOrFile, File highestAncestorToDelete) {
        deleteOnExitRecursively(directoryToCleanOrFile);
        deleteOnExitEmptyParentsUpTo(directoryToCleanOrFile, highestAncestorToDelete);
    }

    /** as {@link File#mkdirs()} but throwing on failure and returning the directory made for fluent convenience */
    public static File mkdirs(File dir) {
        dir.mkdirs();
        if (dir.isDirectory()) {
            return dir;
        }
        throw Exceptions.propagate(new IOException("Failed to create directory " + dir + 
                (dir.isFile() ? "(is file)" : "")));
    }

    /** writes given contents to a temporary file which will be deleted on exit */
    public static File writeToTempFile(InputStream is, String prefix, String suffix) {
        return writeToTempFile(is, new File(Os.tmp()), prefix, suffix);
    }
    
    /** writes given contents to a temporary file which will be deleted on exit, located under the given directory */
    public static File writeToTempFile(InputStream is, File tempDir, String prefix, String suffix) {
        Preconditions.checkNotNull(is, "Input stream required to create temp file for %s*%s", prefix, suffix);
        mkdirs(tempDir);
        File tempFile;
        try {
            tempFile = File.createTempFile(prefix, suffix, tempDir);
        } catch (IOException e) {
            throw Throwables.propagate(new IOException("Unable to create temp file in "+tempDir+" of form "+prefix+"-"+suffix, e));
        }
        tempFile.deleteOnExit();

        OutputStream out = null;
        try {
            out = new FileOutputStream(tempFile);
            ByteStreams.copy(is, out);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        } finally {
            Streams.closeQuietly(is);
            Streams.closeQuietly(out);
        }
        return tempFile;
    }

    public static File writePropertiesToTempFile(Properties props, String prefix, String suffix) {
        return writePropertiesToTempFile(props, new File(Os.tmp()), prefix, suffix);
    }
    
    public static File writePropertiesToTempFile(Properties props, File tempDir, String prefix, String suffix) {
        Preconditions.checkNotNull(props, "Properties required to create temp file for %s*%s", prefix, suffix);
        File tempFile;
        try {
            tempFile = File.createTempFile(prefix, suffix, tempDir);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
        tempFile.deleteOnExit();

        OutputStream out = null;
        try {
            out = new FileOutputStream(tempFile);
            props.store(out, "Auto-generated by Brooklyn");
        } catch (IOException e) {
            throw Throwables.propagate(e);
        } finally {
            Streams.closeQuietly(out);
        }
        return tempFile;
    }

    /**
     * Tidy up a file path.
     * <p>
     * Removes duplicate or trailing path separators (Unix style forward
     * slashes only), replaces initial {@literal ~} with the
     * value of {@link #home()} and folds out use of {@literal ..} and
     * {@literal .} path segments.
     *
     * @see com.google.common.io.Files#simplifyPath(String)
     */
    public static String tidyPath(String path) {
        Preconditions.checkNotNull(path, "path");
        Iterable<String> segments = Splitter.on("/").split(Files.simplifyPath(path));
        if (Iterables.get(segments, 0).equals("~")) { // Always at least one segment after simplifyPath
            segments = Iterables.concat(ImmutableSet.of(Os.home()), Iterables.skip(segments, 1));
        }
        String result = Joiner.on("/").join(segments);
        if (log.isTraceEnabled() && !result.equals(path)) log.trace("Quietly changing '{}' to '{}'", path, result);
        return result;
    }

    public static boolean isAbsolute(String path) {
        if (path.startsWith(File.separator)) return true;
        if (Os.isMicrosoftWindows()) {
            if (path.length()>=3 && path.substring(1).startsWith(":\\"))
                return true;
        } else {
            if (path.equals("~") || path.startsWith("~/")) return true;
        }
        return false;
    }

    public static boolean isMicrosoftWindows() {
        if (System.getProperty("os.name").toLowerCase().indexOf("microsoft")>=0)
            return true;
        return false;
    }

}
