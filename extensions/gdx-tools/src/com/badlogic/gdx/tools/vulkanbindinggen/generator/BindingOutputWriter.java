package com.badlogic.gdx.tools.vulkanbindinggen.generator;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

public class BindingOutputWriter {

    private final FileHandle outputDirectory;

    public BindingOutputWriter(String outputDirPath) {
        // Use Gdx.files.local for path relative to application working dir
        this.outputDirectory = Gdx.files.local(outputDirPath);
    }

    public boolean write(String filename, String content) {
        try {
            // Ensure the directory exists
            outputDirectory.mkdirs();

            FileHandle file = outputDirectory.child(filename);
            file.writeString(content, false, "UTF-8"); // Overwrite, UTF-8 encoding
            return true;
        } catch (Exception e) {
            Gdx.app.error("OUTPUT_WRITER", "Failed to write file: " + filename, e);
            return false;
        }
    }
}