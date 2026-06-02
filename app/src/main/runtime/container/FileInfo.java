package com.winlator.cmod.runtime.container;

import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.util.StringUtils;
import com.winlator.cmod.runtime.wine.MSLink;
import com.winlator.cmod.runtime.wine.WineUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

public class FileInfo implements Comparable<FileInfo> {
    public enum Type {FILE, DIRECTORY, DRIVE}
    public final String name;
    public final String path;
    public final Type type;
    private final Container container;

    public FileInfo(Container container, String path, Type type) {
        this(container, FileUtils.getName(path), path, type);
    }

    public FileInfo(Container container, String name, String path, Type type) {
        this.container = container;
        this.name = name;
        this.path = StringUtils.removeEndSlash(path);
        this.type = type;
    }

    public ArrayList<FileInfo> list() {
        ArrayList<FileInfo> result = new ArrayList<>();
        File parent = toFile();

        if (parent.isDirectory()) {
            File[] files = parent.listFiles();
            if (files != null) {
                for (File file : files) {
                    result.add(new FileInfo(container, file.getPath(), file.isDirectory() ? Type.DIRECTORY : Type.FILE));
                }
            }
        }

        Collections.sort(result);
        return result;
    }

    public long getSize() {
        return type == Type.FILE ? toFile().length() : 0;
    }

    public int getItemCount() {
        File file = toFile();
        String[] items = file.list();
        return items != null ? items.length : 0;
    }

    public File toFile() {
        return new File(path);
    }

    public boolean renameTo(String newName) {
        File from = toFile();
        File to = new File(from.getParentFile(), newName);
        return !to.exists() && from.renameTo(to);
    }

    public String getDisplayName() {
        return name;
    }

    @Override
    public int compareTo(FileInfo other) {
        int value = Boolean.compare(other.type == FileInfo.Type.DRIVE, this.type == FileInfo.Type.DRIVE);
        if (value == 0) value = Boolean.compare(other.type == FileInfo.Type.DIRECTORY, this.type == FileInfo.Type.DIRECTORY);
        if (value == 0) value = this.name.compareTo(other.name);
        return value;
    }
}
