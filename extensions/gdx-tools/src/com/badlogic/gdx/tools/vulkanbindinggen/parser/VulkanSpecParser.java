package com.badlogic.gdx.tools.vulkanbindinggen.parser;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanCommand;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanExtension;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanFeature;
import com.badlogic.gdx.tools.vulkanbindinggen.data.VulkanParam;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VulkanSpecParser {

    private Map<String, VulkanCommand> commands = new LinkedHashMap<>();
    private Map<String, VulkanFeature> coreVersions = new LinkedHashMap<>();
    private Map<String, VulkanExtension> extensions = new LinkedHashMap<>();
    // Potentially add maps for types, enums, structs if needed later

    public void parse(FileHandle xmlFile) throws Exception {
        if (xmlFile == null || !xmlFile.exists()) {
            throw new GdxRuntimeException("vk.xml file not found or not provided.");
        }

        commands.clear();
        coreVersions.clear();
        extensions.clear();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc;

        try (InputStream is = xmlFile.read()) {
            doc = builder.parse(is);
        }

        doc.getDocumentElement().normalize();

        // --- 1. Parse Commands ---
        NodeList commandNodes = doc.getElementsByTagName("command");
        for (int i = 0; i < commandNodes.getLength(); i++) {
            Node node = commandNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element commandElement = (Element) node;
                // Skip aliases for now, handle them if necessary later
                if (commandElement.hasAttribute("alias")) {
                    continue;
                }

                NodeList protoNodes = commandElement.getElementsByTagName("proto");
                if (protoNodes.getLength() == 0 || protoNodes.item(0) == null || protoNodes.item(0).getNodeType() != Node.ELEMENT_NODE) {
                    String cmdNameAttr = commandElement.getAttribute("name");
                    if (cmdNameAttr != null && !cmdNameAttr.isEmpty()) {
                        //System.err.println("Warning: Command element '" + cmdNameAttr + "' missing 'proto' child. Skipping command parameters/return type parsing.");
                        continue;
                    } else {
                        //System.err.println("Warning: Command element missing 'proto' child and 'name' attribute. Skipping command entirely.");
                        continue;
                    }
                }
                Element proto = (Element) protoNodes.item(0);
                String commandName = getTextContent(proto, "name");
                String returnType = getTextContent(proto, "type");

                if (commandName == null || commandName.isEmpty()) {
                    commandName = commandElement.getAttribute("name");
                    if (commandName == null || commandName.isEmpty()) {
                        System.err.println("Warning: Failed to extract command name from proto or attribute for element. Skipping command.");
                        continue;
                    }
                    System.err.println("Warning: Failed to get command name from proto, using attribute name '" + commandName + "' instead.");
                }

                VulkanCommand cmd = new VulkanCommand(commandName);
                cmd.returnType = returnType;

                NodeList paramNodes = commandElement.getElementsByTagName("param");
                for (int j = 0; j < paramNodes.getLength(); j++) {
                    Node paramNode = paramNodes.item(j);
                    if (paramNode.getNodeType() == Node.ELEMENT_NODE) {
                        Element paramElement = (Element) paramNode;
                        NodeList typeNodes = paramElement.getElementsByTagName("type");
                        NodeList nameNodes = paramElement.getElementsByTagName("name");

                        if (typeNodes.getLength() > 0 && nameNodes.getLength() > 0 && typeNodes.item(0) != null && nameNodes.item(0) != null) {
                            String paramType = getTextContent(paramElement, "type");
                            String paramName = getTextContent(paramElement, "name");

                            if (paramType.isEmpty()) {
                                System.err.println("Warning: Extracted empty type for param '" + paramName +"' in command '" + commandName + "'. Skipping param. Param text: " + paramElement.getTextContent());
                                continue;
                            }
                            if (paramName.isEmpty()) {
                                System.err.println("Warning: Extracted empty name for param type '" + paramType +"' in command '" + commandName + "'. Skipping param. Param text: " + paramElement.getTextContent());
                                continue;
                            }
                            String rawType = extractRawType(paramElement);
                            cmd.params.add(new VulkanParam(paramType, paramName, rawType));
                        } else {
                            String paramText = paramElement.getTextContent().trim();
                            System.out.println("Info: Skipping param with no <type>/<name> tags in command '" + commandName + "': " + paramText);
                        }
                    }
                }
                cmd.determineLevel();
                commands.put(commandName, cmd);
            }
        }

        // --- 2. Parse Features (Core Versions) ---
        VulkanFeature vk10FeatureData = null; // Hold the successfully parsed 1.0 data

        NodeList featureNodes = doc.getElementsByTagName("feature");
        for (int i = 0; i < featureNodes.getLength(); i++) {
            Node node = featureNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element featureElement = (Element) node;
                String api = featureElement.getAttribute("api");
                String featureName = featureElement.getAttribute("name");
                String number = featureElement.getAttribute("number");

                // We only care about standard Vulkan API features for the coreVersions map
                if (api == null || !api.contains("vulkan")) { // Check if "vulkan" is missing or api is null
                    //System.out.println("DEBUG PARSER FEATURE: Skipping feature '" + featureName + "' because its API ('" + api + "') does not contain 'vulkan'");
                    continue; // Skip features not applicable to standard Vulkan
                }

                // Parse requirements into a temporary feature object for ALL standard Vulkan features found
                VulkanFeature currentFeature = new VulkanFeature(featureName, api, number);
                NodeList requireNodes = featureElement.getElementsByTagName("require");
                for (int j = 0; j < requireNodes.getLength(); j++) {
                    Element requireElement = (Element) requireNodes.item(j);
                    NodeList commandRefs = requireElement.getElementsByTagName("command");
                    for (int k = 0; k < commandRefs.getLength(); k++) {
                        String cmdName = ((Element) commandRefs.item(k)).getAttribute("name");
                        if ("VK_VERSION_1_0".equals(featureName)) { // Keep this debug log
                            //System.out.println("DEBUG PARSER FEATURE 1.0: Found require command: " + cmdName);
                        }
                        currentFeature.requiredCommandNames.add(cmdName);
                    }
                    // Add parsing for required types/enums here if needed later
                }

                // Special handling: If this IS the VK_VERSION_1_0 feature, store its data
                if ("VK_VERSION_1_0".equals(featureName)) {
                    System.out.println("DEBUG PARSER FEATURE: Captured VK_VERSION_1_0 feature data (Number attribute: " + (number != null ? number : "missing") + ")");
                    vk10FeatureData = currentFeature; // Save the populated feature object
                }
                // Store other standard versions (1.1+) ONLY if they have a number
                else if (number != null && !number.isEmpty()) {
                    System.out.println("DEBUG PARSER FEATURE: Storing standard Vulkan feature '" + featureName + "' under number '" + number + "'");
                    coreVersions.put(number, currentFeature); // Store 1.1, 1.2, etc.
                } else {
                    System.out.println("DEBUG PARSER FEATURE: Skipping storing feature '" + featureName + "' - not VK_VERSION_1_0 and lacks a 'number'.");
                }
            }
        }

        // After looping through all features, ensure the captured 1.0 data is in the map
        if (vk10FeatureData != null) {
            System.out.println("DEBUG PARSER FEATURE: Adding captured VK_VERSION_1_0 data to map with key '1.0'.");
            // Ensure number is set correctly for consistency, even if missing in XML tag
            if (vk10FeatureData.number == null || vk10FeatureData.number.isEmpty()) {
                vk10FeatureData.number = "1.0";
            }
            coreVersions.put("1.0", vk10FeatureData); // Add/overwrite map entry with correct 1.0 data
        } else {
            // This case should ideally not happen if vk.xml is standard
            System.out.println("DEBUG PARSER FEATURE: WARNING - VK_VERSION_1_0 feature tag was not found or not processed! Manually adding EMPTY feature for 1.0 as fallback.");
            coreVersions.put("1.0", new VulkanFeature("VK_VERSION_1_0", "vulkan", "1.0"));
        }


        // --- 3. Parse Extensions --- (Assuming this logic is correct)
        NodeList extensionsRoot = doc.getElementsByTagName("extensions");
        if (extensionsRoot.getLength() > 0) {
            NodeList extensionNodes = ((Element) extensionsRoot.item(0)).getElementsByTagName("extension");
            for (int i = 0; i < extensionNodes.getLength(); i++) {
                Node node = extensionNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element extElement = (Element) node;
                    String extName = extElement.getAttribute("name");
                    String supported = extElement.getAttribute("supported");
                    // ... (rest of extension parsing) ...
                    VulkanExtension ext = new VulkanExtension(extName);
                    ext.number = extElement.getAttribute("number");
                    ext.type = extElement.getAttribute("type");
                    ext.supported = supported;
                    ext.requiresCore = extElement.getAttribute("requiresCore");

                    NodeList requireNodes = extElement.getElementsByTagName("require");
                    for (int j = 0; j < requireNodes.getLength(); j++) {
                        Element requireElement = (Element) requireNodes.item(j);
                        // ...
                        NodeList commandRefs = requireElement.getElementsByTagName("command");
                        for (int k = 0; k < commandRefs.getLength(); k++) {
                            String cmdName = ((Element) commandRefs.item(k)).getAttribute("name");
                            ext.requiredCommandNames.add(cmdName);
                        }
                        // ...
                    }
                    extensions.put(extName, ext);
                }
            }
        }

        // --- Final Sorting and Summary ---

        // Sort versions for UI presentation (make sure 1.0 is handled correctly if parsing fails)
        List<Map.Entry<String, VulkanFeature>> sortedVersions = new ArrayList<>(coreVersions.entrySet());
        Collections.sort(sortedVersions, Comparator.comparing(e -> parseVersion(e.getKey())));
        coreVersions = sortedVersions.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        // Sort extensions alphabetically for UI presentation
        List<Map.Entry<String, VulkanExtension>> sortedExtensions = new ArrayList<>(extensions.entrySet());
        Collections.sort(sortedExtensions, Comparator.comparing(Map.Entry::getKey));
        extensions = sortedExtensions.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        // Final check on the commands map
        System.out.println("DEBUG PARSER: Checking final commands map...");
        if (commands.containsKey("vkGetInstanceProcAddr")) {
            System.out.println("DEBUG PARSER: vkGetInstanceProcAddr IS PRESENT in parsed commands map.");
        } else {
            System.out.println("DEBUG PARSER: vkGetInstanceProcAddr IS MISSING from parsed commands map!");
        }

        System.out.println("Successfully parsed vk.xml.");
        System.out.println("Found " + commands.size() + " commands.");
        System.out.println("Found " + coreVersions.size() + " core versions in map (check UI for display)."); // Changed message slightly
        System.out.println("Found " + extensions.size() + " extensions.");
    }

    // Helper to safely get text content from a child element
    private String getTextContent(Element parent, String childTagName) {
        // ... (implementation unchanged)
        if (parent == null) {
            System.err.println("Warning: getTextContent called with null parent for tag: " + childTagName);
            return "";
        }
        NodeList nodes = parent.getElementsByTagName(childTagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    // Helper to get raw type text (including *, const)
    private String extractRawType(Element paramElement) {
        // ... (implementation unchanged)
        Node typeNode = paramElement.getElementsByTagName("type").item(0);
        Node nameNode = paramElement.getElementsByTagName("name").item(0);
        String fullText = paramElement.getTextContent().trim();

        if (typeNode == null || nameNode == null) {
            System.err.println("Warning: Missing type or name node in param: " + fullText + " -> defaulting to full text (may cause C issues).");
            return (typeNode != null) ? typeNode.getTextContent().trim() : "ERROR_UNKNOWN_TYPE";
        }

        String typeText = typeNode.getTextContent().trim();
        String nameText = nameNode.getTextContent().trim();

        if (typeText.isEmpty()) {
            System.err.println("Warning: Empty type text in param: " + fullText);
            return "ERROR_EMPTY_TYPE";
        }
        if (nameText.isEmpty()) {
            System.err.println("Warning: Empty name text in param: " + fullText);
        }

        int typeStart = fullText.indexOf(typeText);

        if (typeStart == -1) {
            System.err.println("Warning: Type text '" + typeText + "' not found in param text: '" + fullText + "' -> defaulting to type text.");
            return typeText;
        }

        int typeEnd = typeStart + typeText.length();

        int nameStart = -1;
        if (!nameText.isEmpty()) {
            nameStart = fullText.indexOf(nameText, typeEnd);
        }

        if (nameStart == -1 || nameStart < typeEnd) {
            String textAfterType = fullText.substring(typeEnd).trim();
            System.err.println("Warning: Name '" + nameText + "' not found after type '" + typeText + "' in param: '" + fullText + "'. Reconstructing as: " + typeText + " " + textAfterType);
            return typeText + (textAfterType.isEmpty() ? "" : " " + textAfterType);
        }

        String intermediate = fullText.substring(typeEnd, nameStart).trim();
        return typeText + (intermediate.isEmpty() ? "" : " " + intermediate);
    }

    // Helper to parse "1.x" version strings for sorting
    private static float parseVersion(String version) {
        // ... (implementation unchanged)
        try {
            return Float.parseFloat(version);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    // --- Getters ---
    public Map<String, VulkanCommand> getCommands() {
        return Collections.unmodifiableMap(commands);
    }

    public Map<String, VulkanFeature> getCoreVersions() {
        return Collections.unmodifiableMap(coreVersions);
    }

    public Map<String, VulkanExtension> getExtensions() {
        return Collections.unmodifiableMap(extensions);
    }

    public VulkanCommand getCommand(String name) {
        return commands.get(name);
    }
}