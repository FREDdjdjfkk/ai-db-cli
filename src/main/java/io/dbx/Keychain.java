package io.dbx;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** macOS login keychain. Passwords travel on stdin, never in argv or diagnostics. */
final class Keychain {
    private static final String SERVICE = "io.dbx.credentials";
    private final String executable;
    Keychain() { this("/usr/bin/security"); }
    Keychain(String executable) { this.executable = executable; }

    static void validateReference(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9_.:-]{1,128}")) throw Failure.usage("Invalid keychain reference");
    }
    static String quote(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw Failure.usage("Keychain passwords must be a single line without NUL characters");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private record Result(int exit, byte[] out) {}
    private Result run(List<String> args, String input) {
        Process process = null;
        try {
            var command = new ArrayList<String>(); command.add(executable); command.addAll(args);
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // Read concurrently to avoid a full stdout pipe blocking security's exit.
            Process active = process;
            var reader = new java.util.concurrent.FutureTask<byte[]>(() -> active.getInputStream().readNBytes(131073));
            var thread = new Thread(reader, "dbx-keychain-output"); thread.setDaemon(true); thread.start();
            try (var stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new Failure(5, "KEYCHAIN_UNAVAILABLE", "Keychain did not respond; unlock the login keychain and retry");
            byte[] output = reader.get(1, TimeUnit.SECONDS);
            if (output.length > 131072) throw new Failure(4, "KEYCHAIN_ERROR", "Unexpected keychain output");
            return new Result(process.exitValue(), output);
        } catch (Failure f) { throw f; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(5,"KEYCHAIN_UNAVAILABLE","Keychain operation interrupted"); }
        catch (Exception e) { throw new Failure(4, "KEYCHAIN_UNAVAILABLE", "macOS Keychain is unavailable on this system"); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    String read(String reference) {
        validateReference(reference);
        Result r = run(List.of("find-generic-password", "-a", reference, "-s", SERVICE, "-w"), null);
        if (r.exit == 44) throw new Failure(3,"KEYCHAIN_ACCESS_REQUIRED","A saved password reference exists, but the item is missing or inaccessible to this process; check login keychain and execution permissions before replacing credentials");
        if (r.exit != 0) throw new Failure(4,"KEYCHAIN_UNAVAILABLE","Saved password could not be read; unlock the login keychain and retry");
        String value = new String(r.out, StandardCharsets.UTF_8);
        // security appends one output newline; preserve the password's other whitespace.
        if (value.endsWith("\n")) value = value.substring(0, value.length()-1);
        if (value.isEmpty()) throw new Failure(2,"MISSING_SECRET","Saved password is empty");
        return value;
    }
    boolean contains(String reference) {
        validateReference(reference);
        Result r=run(List.of("find-generic-password","-a",reference,"-s",SERVICE),null);
        if (r.exit==44) return false;
        if (r.exit!=0) throw new Failure(4,"KEYCHAIN_UNAVAILABLE","Cannot inspect login keychain");
        return true;
    }
    void save(String reference, String password) {
        validateReference(reference);
        if (password == null || password.isEmpty() || password.length()>65536) throw Failure.usage("Password must contain 1..65536 characters");
        String command="add-generic-password -U -a "+quote(reference)+" -s "+quote(SERVICE)+" -w "+quote(password)+"\n";
        Result r=run(List.of("-i"),command);
        if (r.exit!=0) throw new Failure(4,"KEYCHAIN_ERROR","Password could not be saved to login keychain");
        // Interactive security may return zero after an individual command failed: verify persistence.
        String saved=read(reference);
        if (!MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),saved.getBytes(StandardCharsets.UTF_8)))
            throw new Failure(4,"KEYCHAIN_ERROR","Saved password verification failed");
    }
}
