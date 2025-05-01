package com.badlogic.gdx.tools.vulkanbindinggen.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanCommand;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanExtension;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanFeature;
import com.badlogic.gdx.tools.vulkanbindinggen.generator.BindingOutputWriter;
import com.badlogic.gdx.tools.vulkanbindinggen.generator.VulkanCGenerator;
import com.badlogic.gdx.tools.vulkanbindinggen.generator.VulkanJavaGenerator;
import com.badlogic.gdx.tools.vulkanbindinggen.parser.VulkanSpecParser;
import com.badlogic.gdx.utils.Align;


import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class BindingGeneratorUI {

    private final Skin skin;
    private final VulkanSpecParser specParser;
    private final Table rootTable;
    private final Table contentTable; // Table inside ScrollPane

    // Store UI elements for state management
    private final Map<String, CheckBox> coreVersionCheckBoxes = new HashMap<>();
    private final Map<String, CheckBox> extensionCheckBoxes = new HashMap<>();
    private final Map<String, Table> extensionGroupTables = new HashMap<>(); // Stores the content table for each group
    private final Map<String, Button> extensionGroupExpandButtons = new HashMap<>(); // Stores the expand/collapse button

    private static final String OUTPUT_DIR = "generated/"; // Hardcoded for now


    public BindingGeneratorUI(Skin skin, VulkanSpecParser specParser) {
        this.skin = skin;
        this.specParser = specParser;
        this.rootTable = new Table(skin);
        this.rootTable.setFillParent(true);
        this.contentTable = new Table(skin);
    }

    public Table buildUI() {
        rootTable.pad(10);
        rootTable.defaults().pad(5);

        // --- Title ---
        rootTable.add(new Label("Vulkan Binding Generator", skin, "default-font", com.badlogic.gdx.graphics.Color.YELLOW)).colspan(2).center().padBottom(10);
        rootTable.row();

        // --- Scroll Pane ---
        contentTable.align(Align.topLeft); // Align content to top-left
        ScrollPane scrollPane = new ScrollPane(contentTable, skin);
        scrollPane.setFadeScrollBars(false);
        scrollPane.setScrollingDisabled(true, false); // Disable horizontal scroll

        rootTable.add(scrollPane).expand().fill().colspan(2);
        rootTable.row();

        // --- Bottom Button Bar ---
        Table buttonTable = new Table(skin);
        buttonTable.defaults().pad(5).minWidth(150);

        TextButton generateButton = new TextButton("Generate Bindings", skin);
        TextButton resetButton = new TextButton("Reset Selection", skin);
        TextButton exitButton = new TextButton("Exit", skin);

        buttonTable.add(generateButton);
        buttonTable.add(resetButton);
        buttonTable.add(exitButton);

        rootTable.add(buttonTable).colspan(2).fillX().padTop(10);

        // --- Populate Content ---
        populateCoreVersions();
        populateExtensions();
        resetSelection(); // Set initial state

        // --- Add Listeners ---
        generateButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                generateBindings();
            }
        });

        resetButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                resetSelection();
            }
        });

        exitButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                Gdx.app.exit();
            }
        });

        return rootTable;
    }

    private void populateCoreVersions() {
        contentTable.add(new Label("Core Vulkan Versions", skin)).left().padBottom(5).colspan(2);
        contentTable.row();

        Table versionsTable = new Table(skin);
        versionsTable.defaults().left().padLeft(20).padBottom(2);

        // Sort versions numerically if not already sorted by parser
        List<String> sortedVersions = new ArrayList<>(specParser.getCoreVersions().keySet());
        Collections.sort(sortedVersions, Comparator.comparing(BindingGeneratorUI::parseVersionSafe));


        for (String version : sortedVersions) {
            VulkanFeature feature = specParser.getCoreVersions().get(version);
            CheckBox cb = new CheckBox(" Vulkan " + version, skin);
            coreVersionCheckBoxes.put(version, cb);

            // Vulkan 1.0 and 1.1 are required and always selected
            if ("1.0".equals(version) || "1.1".equals(version)) {
                cb.setChecked(true);
                cb.setDisabled(true);
            } else {
                // Add listener to selectable versions
                cb.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        updateExtensionEnablementBasedOnCore();
                    }
                });
            }

            versionsTable.add(cb).left();
            versionsTable.row();
        }
        contentTable.add(versionsTable).left().fillX().padBottom(15).colspan(2);
        contentTable.row();
    }

    private void populateExtensions() {
        contentTable.add(new Label("Optional Features / Extensions", skin)).left().padBottom(5).colspan(2);
        contentTable.row();

        // Group extensions by the core version they *require* (or group 'other'/'common')
        // This is a simplification. A more accurate grouping might involve extension dependencies.
        // Let's group by required Core version primarily. Extensions requiring nothing or 1.0/1.1 go into a general group.
        Map<String, List<VulkanExtension>> groupedExtensions = new TreeMap<>(Comparator.comparing(BindingGeneratorUI::parseVersionSafe));
        List<VulkanExtension> commonExtensions = new ArrayList<>();

        for (VulkanExtension ext : specParser.getExtensions().values()) {
            // Skip disabled extensions unless you want to show them greyed out
            if ("disabled".equals(ext.supported)) {
                continue;
            }

            String requiredCore = ext.requiresCore;
            if (requiredCore == null || requiredCore.isEmpty() || "1.0".equals(requiredCore) || "1.1".equals(requiredCore)) {
                commonExtensions.add(ext);
            } else {
                groupedExtensions.computeIfAbsent(requiredCore, k -> new ArrayList<>()).add(ext);
            }
        }


        // Add "Common/Core" Extensions Group (if any)
        if (!commonExtensions.isEmpty()) {
            addExtensionGroup("Common Extensions (Require <= 1.1)", commonExtensions);
        }

        // Add Groups for Extensions requiring specific core versions
        for (Map.Entry<String, List<VulkanExtension>> entry : groupedExtensions.entrySet()) {
            String coreVersion = entry.getKey();
            List<VulkanExtension> extsInGroup = entry.getValue();
            // Sort extensions within the group alphabetically
            extsInGroup.sort(Comparator.comparing(e -> e.name));
            addExtensionGroup("Features requiring Vulkan " + coreVersion, extsInGroup);
        }

        updateExtensionEnablementBasedOnCore(); // Initial enablement state
    }

    private void addExtensionGroup(String groupName, List<VulkanExtension> extensionsInGroup) {
        // --- Collapsible Header ---
        Table headerTable = new Table(skin);
        headerTable.defaults().pad(2);
        headerTable.left();

        final Table content = new Table(skin);
        content.defaults().left().padLeft(40).padBottom(2); // Indent content
        content.setVisible(false); // Start collapsed
        content.top().left(); // Align content within its cell

        final Label arrowLabel = new Label("▶", skin); // ">" symbol for expand
        Button expandButton = new Button(skin); // Use a basic button for the whole header row click
        expandButton.add(arrowLabel).padRight(5);
        expandButton.add(new Label(groupName, skin)).expandX().left();
        headerTable.add(expandButton).fillX().expandX();

        extensionGroupTables.put(groupName, content);
        extensionGroupExpandButtons.put(groupName, expandButton);


        expandButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                boolean isVisible = content.isVisible();
                content.setVisible(!isVisible);
                arrowLabel.setText(isVisible ? "▶" : "▼"); // Toggle arrow
            }
        });

        // --- Content Checkboxes ---
        for (VulkanExtension ext : extensionsInGroup) {
            CheckBox cb = new CheckBox(" " + ext.name, skin);
            cb.setDisabled(true); // Start disabled, enable based on core version selection
            extensionCheckBoxes.put(ext.name, cb);
            content.add(cb).left();
            content.row();
        }

        // Add header and content placeholder to the main content table
        contentTable.add(headerTable).left().fillX().expandX().colspan(2);
        contentTable.row();
        contentTable.add(content).left().fillX().expandX().colspan(2).padBottom(10);
        contentTable.row();
    }


    private void updateExtensionEnablementBasedOnCore() {
        // Determine highest selected core version
        float maxSelectedVersion = 1.1f; // Base requirement
        for (Map.Entry<String, CheckBox> entry : coreVersionCheckBoxes.entrySet()) {
            if (!entry.getKey().equals("1.0") && !entry.getKey().equals("1.1") && entry.getValue().isChecked()) {
                maxSelectedVersion = Math.max(maxSelectedVersion, parseVersionSafe(entry.getKey()));
            }
        }

        // Enable/disable individual extension checkboxes based on requirements
        for (VulkanExtension ext : specParser.getExtensions().values()) {
            if ("disabled".equals(ext.supported)) continue; // Skip disabled

            CheckBox cb = extensionCheckBoxes.get(ext.name);
            if (cb == null) continue; // Skip if UI element wasn't created

            boolean coreRequirementMet = true;
            if (ext.requiresCore != null && !ext.requiresCore.isEmpty()) {
                coreRequirementMet = parseVersionSafe(ext.requiresCore) <= maxSelectedVersion;
            }

            cb.setDisabled(!coreRequirementMet);
            if (!coreRequirementMet) {
                cb.setChecked(false); // Uncheck if core requirement is no longer met
            }
            // TODO: Add logic here to handle extension dependencies if implementing that
        }

        // Optionally, disable entire groups if their core version is unchecked
        // For now, we disable individual checkboxes which is clearer.
    }


    private void resetSelection() {
        // Reset Core Versions
        for (Map.Entry<String, CheckBox> entry : coreVersionCheckBoxes.entrySet()) {
            String version = entry.getKey();
            CheckBox cb = entry.getValue();
            if ("1.0".equals(version) || "1.1".equals(version)) {
                cb.setChecked(true);
                cb.setDisabled(true); // Ensure they remain disabled
            } else {
                cb.setChecked(false);
                cb.setDisabled(false); // Re-enable checkboxes for selection
            }
        }

        // Reset Extensions
        for (CheckBox cb : extensionCheckBoxes.values()) {
            cb.setChecked(false);
            // Enablement will be reset by the update call below
        }

        // Collapse all extension groups
        for (Map.Entry<String, Table> entry : extensionGroupTables.entrySet()) {
            entry.getValue().setVisible(false);
            Button btn = extensionGroupExpandButtons.get(entry.getKey());
            if (btn != null && btn.getChildren().size > 0 && btn.getChildren().first() instanceof Label) {
                ((Label) btn.getChildren().first()).setText("▶");
            }
        }


        updateExtensionEnablementBasedOnCore(); // Apply initial/reset enablement rules
        System.out.println("UI Selection Reset.");
    }

    private void generateBindings() {
        System.out.println("Generating bindings...");

        Set<String> selectedCommandNames = new HashSet<>();
        final String TARGET_COMMAND = "vkGetInstanceProcAddr"; // For specific logging

        // 1. Add commands from selected core versions
        float highestSelectedCore = 1.1f; // Start with base
        for (Map.Entry<String, CheckBox> entry : coreVersionCheckBoxes.entrySet()) {
            if (entry.getValue().isChecked() && !entry.getValue().isDisabled()) {
                highestSelectedCore = Math.max(highestSelectedCore, parseVersionSafe(entry.getKey()));
            }
        }
        //System.out.println("DEBUG SELECT: Highest selected core version: " + highestSelectedCore);

        // Add commands for all versions up to the highest selected one
        for (Map.Entry<String, VulkanFeature> featureEntry : specParser.getCoreVersions().entrySet()) {
            String currentVersion = featureEntry.getKey();
            VulkanFeature feature = featureEntry.getValue();
            boolean isProcessingTargetVersion = "1.0".equals(currentVersion); // Check if we are processing the version that *should* contain vkGetInstanceProcAddr

            if (parseVersionSafe(currentVersion) <= highestSelectedCore) {
                //System.out.println("DEBUG SELECT: Processing core version: " + currentVersion);

                if (isProcessingTargetVersion) {
                    //System.out.println("DEBUG SELECT:   Checking required commands for Vulkan 1.0:");
                    if (feature.requiredCommandNames.contains(TARGET_COMMAND)) {
                       // System.out.println("DEBUG SELECT:     Vulkan 1.0 feature requirements *DO* contain " + TARGET_COMMAND);
                    } else {
                        //System.out.println("DEBUG SELECT:     Vulkan 1.0 feature requirements *DO NOT* contain " + TARGET_COMMAND + " (Problem in Parser or vk.xml structure)");
                    }
                }

                // Add the commands
                int commandsAddedFromThisVersion = 0;
                for (String cmdName : feature.requiredCommandNames) {
                    if (selectedCommandNames.add(cmdName)) {
                        commandsAddedFromThisVersion++;
                        if (cmdName.equals(TARGET_COMMAND)) {
                            //System.out.println("DEBUG SELECT:   >>> ADDED " + TARGET_COMMAND + " to selectedCommandNames from version " + currentVersion);
                        }
                    }
                }
                //System.out.println("DEBUG SELECT:   Added " + commandsAddedFromThisVersion + " new unique commands from version " + currentVersion);
                // System.out.println("Including core commands for version: " + currentVersion); // Original log
            }
        }

        // 2. Add commands from selected extensions
        for (Map.Entry<String, CheckBox> entry : extensionCheckBoxes.entrySet()) {
            if (entry.getValue().isChecked() && !entry.getValue().isDisabled()) {
                VulkanExtension ext = specParser.getExtensions().get(entry.getKey());
                if (ext != null) {
                    //System.out.println("DEBUG SELECT: Processing selected extension: " + ext.name);
                    int commandsAddedFromThisExt = 0;
                    for (String cmdName : ext.requiredCommandNames) {
                        if (selectedCommandNames.add(cmdName)) {
                            commandsAddedFromThisExt++;
                            if (cmdName.equals(TARGET_COMMAND)) { // Check if extension unexpectedly adds it
                                //System.out.println("DEBUG SELECT:   >>> ADDED " + TARGET_COMMAND + " to selectedCommandNames from extension " + ext.name);
                            }
                        }
                    }
                    //System.out.println("DEBUG SELECT:   Added " + commandsAddedFromThisExt + " new unique commands from extension " + ext.name);
                    // System.out.println("Including commands for extension: " + ext.name); // Original log
                }
            }
        }

        // --- DEBUG: Check if target command is in the set BEFORE resolving ---
        if (selectedCommandNames.contains(TARGET_COMMAND)) {
            //System.out.println("DEBUG SELECT: " + TARGET_COMMAND + " IS PRESENT in selectedCommandNames set before final resolution.");
        } else {
            //System.out.println("DEBUG SELECT: " + TARGET_COMMAND + " IS MISSING from selectedCommandNames set before final resolution. (Problem in selection logic above)");
        }
        // --- END DEBUG ---


        // 3. Resolve command objects
        List<VulkanCommand> selectedCommands = new ArrayList<>();
        for (String cmdName : selectedCommandNames) {
            VulkanCommand cmd = specParser.getCommand(cmdName);
            if (cmd != null) {
                selectedCommands.add(cmd);
            } else {
                System.err.println("Warning: Command '" + cmdName + "' requested but not found in parser results.");
                if (cmdName.equals(TARGET_COMMAND)) {
                    System.err.println(">>> CRITICAL WARNING: " + TARGET_COMMAND + " was selected but is MISSING from parser's command map!");
                }
            }
        }

        // Ensure commands are unique and sorted (optional, but good practice)
        selectedCommands = selectedCommands.stream().distinct().sorted(Comparator.comparing(cmd -> cmd.name)).collect(Collectors.toList());

        // --- DEBUG: Check final list ---
        boolean foundInFinalList = false;
        for (VulkanCommand cmd : selectedCommands) {
            if (cmd.name.equals(TARGET_COMMAND)) {
                foundInFinalList = true;
                break;
            }
        }
        if (foundInFinalList) {
            //System.out.println("DEBUG SELECT: " + TARGET_COMMAND + " IS PRESENT in the final resolved selectedCommands list.");
        } else {
           // System.out.println("DEBUG SELECT: " + TARGET_COMMAND + " IS MISSING from the final resolved selectedCommands list. (Problem in resolution or prior steps)");
        }
        // --- END DEBUG ---


        System.out.println("Total unique commands selected for generation: " + selectedCommands.size());
        if (selectedCommands.isEmpty()) {
            System.out.println("No commands selected. Nothing to generate.");
            // Optional: Show a dialog to the user
            return;
        }

        // 4. Instantiate Generators
        VulkanJavaGenerator javaGenerator = new VulkanJavaGenerator("com.badlogic.gdx.backend.vulkan"); // Use your actual target package
        VulkanCGenerator cGenerator = new VulkanCGenerator("com.badlogic.gdx.backend.vulkan");     // Use your actual target package

        // 5. Generate Code Content
        String javaCode = javaGenerator.generate(selectedCommands);
        String cCode = cGenerator.generate(selectedCommands);

        // 6. Write Output Files
        BindingOutputWriter writer = new BindingOutputWriter(OUTPUT_DIR);
        boolean javaSuccess = writer.write("VulkanNative.java", javaCode);
        boolean cSuccess = writer.write("VulkanNative.c", cCode);

        // 7. Report Status (Simple console log, could be a dialog)
        if (javaSuccess && cSuccess) {
            System.out.println("Successfully generated bindings to: " + Gdx.files.local(OUTPUT_DIR).file().getAbsolutePath());
        } else {
            System.err.println("Error writing one or more binding files.");
        }
    }

    // Helper to parse version strings safely
    private static float parseVersionSafe(String version) {
        try {
            if (version == null || version.isEmpty()) return 0.0f;
            return Float.parseFloat(version);
        } catch (NumberFormatException e) {
            return 0.0f; // Or handle error appropriately
        }
    }

    public void resize(int width, int height) {
        // The rootTable uses fillParent=true and the viewport handles resizing.
        // No specific action needed here unless complex layout adjustments are required.
    }
}