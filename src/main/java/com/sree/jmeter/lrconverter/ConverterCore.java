package com.sree.jmeter.lrconverter;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JMX -> LoadRunner Web/HTTP converter.
 *
 * Features:
 *  - One LR script folder per Thread Group.
 *  - HTTP samplers -> web_url / web_submit_data / web_custom_request (simplified).
 *  - Basic correlation:
 *      * RegexExtractor -> web_reg_save_param_ex(RegExp=...).
 *      * JSONPostProcessor -> web_reg_save_param_json(QueryString=...).
 *  - CSV DataSet:
 *      * Copies CSV into script folder.
 *      * Creates matching .dat file for LoadRunner.
 *      * Writes basic parameter config in default.cfg + parameters.prm.
 *  - JMeter vars ${var} -> LoadRunner {var}.
 *  - NO ZIP CREATION.
 */
public class ConverterCore {

    // ==== Public entry ====

    public static void convert(File jmxFile, File outputRoot) throws Exception {
        if (!jmxFile.exists()) {
            throw new IllegalArgumentException("JMX file does not exist: " + jmxFile);
        }
        if (!outputRoot.exists() && !outputRoot.mkdirs()) {
            throw new IllegalStateException("Unable to create output directory: " + outputRoot);
        }

        Document document = parseXml(jmxFile);

        NodeList threadGroups = document.getElementsByTagName("ThreadGroup");
        if (threadGroups.getLength() == 0) {
            System.out.println("No ThreadGroup elements found in JMX.");
        }

        for (int i = 0; i < threadGroups.getLength(); i++) {
            Element tg = (Element) threadGroups.item(i);
            processThreadGroup(tg, document, jmxFile.getParentFile(), outputRoot, i + 1);
        }
    }

    // ==== XML parsing ====

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(file);
    }

    // ==== ThreadGroup processing ====

    private static void processThreadGroup(Element tg,
                                           Document doc,
                                           File jmxDir,
                                           File outputRoot,
                                           int tgIndex) throws Exception {

        String tgName = tg.getAttribute("testname");
        if (tgName == null || tgName.trim().isEmpty()) {
            tgName = "ThreadGroup_" + tgIndex;
        }

        String scriptDirName = "Script_" + sanitizeName(tgName);
        File scriptDir = new File(outputRoot, scriptDirName);
        if (!scriptDir.exists() && !scriptDir.mkdirs()) {
            throw new IllegalStateException("Unable to create script directory: " + scriptDir);
        }

        // Parse CSV DataSets and copy CSVs + create .dat + parameter definitions
        List<CsvParameterSet> csvParams = parseAndCopyCsvDataSets(doc, jmxDir, scriptDir);

        // Write LR base files
        writeVuserInit(scriptDir);
        writeVuserEnd(scriptDir);
        writeDefaultCfg(scriptDir, csvParams);
        writePrmFile(scriptDir, csvParams);

        // Generate Action.c for this Thread Group
        File actionFile = new File(scriptDir, "Action.c");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(actionFile), StandardCharsets.UTF_8))) {

            writeActionHeader(out);
            out.write("Action()\n{\n");
            out.write("    int rc = 0;\n\n");

            Element tgTree = findFollowingHashTree(tg);
            if (tgTree != null) {
                processHashTree(tgTree, out, false, scriptDir);
            } else {
                log(scriptDir, "WARNING: No hashTree found for ThreadGroup '" + tgName + "'.");
            }

            out.write("\n    return 0;\n");
            out.write("}\n");
        }

        // Optional simple log
        writeConversionLog(scriptDir, tgName, csvParams);
    }

    // ==== LR files ====

    private static void writeVuserInit(File scriptDir) throws Exception {
        File f = new File(scriptDir, "vuser_init.c");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {

            out.write("#include \"lrun.h\"\n");
            out.write("#include \"web_api.h\"\n");
            out.write("#include \"lrw_custom_body.h\"\n\n");
            out.write("vuser_init()\n{\n");
            out.write("    // TODO: Add login / init steps if needed\n");
            out.write("    return 0;\n");
            out.write("}\n");
        }
    }

    private static void writeVuserEnd(File scriptDir) throws Exception {
        File f = new File(scriptDir, "vuser_end.c");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {

            out.write("#include \"lrun.h\"\n");
            out.write("#include \"web_api.h\"\n");
            out.write("#include \"lrw_custom_body.h\"\n\n");
            out.write("vuser_end()\n{\n");
            out.write("    // TODO: Add logout / cleanup if needed\n");
            out.write("    return 0;\n");
            out.write("}\n");
        }
    }

    private static void writeActionHeader(BufferedWriter out) throws Exception {
        out.write("#include \"lrun.h\"\n");
        out.write("#include \"web_api.h\"\n");
        out.write("#include \"lrw_custom_body.h\"\n\n");
    }

    private static void writeDefaultCfg(File scriptDir, List<CsvParameterSet> csvParams) throws Exception {
        File f = new File(scriptDir, "default.cfg");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {

            out.write("[General]\n");
            out.write("DefaultRunLogic=Action\n");
            out.write("\n");
            out.write("[Actions]\n");
            out.write("vuser_init=vuser_init.c\n");
            out.write("Action=Action.c\n");
            out.write("vuser_end=vuser_end.c\n");
            out.write("\n");
            out.write("[Parameters]\n\n");

            for (CsvParameterSet set : csvParams) {
                if (set.variableNames.isEmpty()) {
                    continue;
                }
                String dataFile = set.datFileName != null ? set.datFileName : set.fileName;
                for (int i = 0; i < set.variableNames.size(); i++) {
                    String var = set.variableNames.get(i).trim();
                    if (var.isEmpty()) continue;

                    String paramSection = sanitizeName(var);
                    out.write("[" + paramSection + "]\n");
                    out.write("Type=File\n");
                    out.write("FileName=" + dataFile + "\n");
                    out.write("Column=" + (i + 1) + "\n");
                    out.write("Delimiter=" + (set.delimiter == null || set.delimiter.isEmpty() ? "," : set.delimiter) + "\n");
                    out.write("SelectNextRow=Sequential\n");
                    out.write("WhenOutOfRange=Continue\n\n");
                }
            }
        }
    }

    private static void writePrmFile(File scriptDir, List<CsvParameterSet> csvParams) throws Exception {
        File prmFile = new File(scriptDir, "parameters.prm");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(prmFile), StandardCharsets.UTF_8))) {

            out.write("; Basic PRM mapping generated from JMeter CSV Data Set Config\n");
            out.write("; Please open in VuGen and refine as per your LoadRunner version.\n\n");

            for (CsvParameterSet set : csvParams) {
                if (set.variableNames.isEmpty()) {
                    continue;
                }
                String dataFile = set.datFileName != null ? set.datFileName : set.fileName;
                for (String var : set.variableNames) {
                    String varTrim = var.trim();
                    if (varTrim.isEmpty()) continue;

                    out.write("[Parameter]\n");
                    out.write("Name=" + sanitizeName(varTrim) + "\n");
                    out.write("Type=File\n");
                    out.write("FileName=" + dataFile + "\n");
                    out.write("ColumnDelimiter=" + (set.delimiter == null || set.delimiter.isEmpty() ? "," : set.delimiter) + "\n");
                    out.write("UpdateMode=Sequential\n");
                    out.write("WhenOutOfRange=Continue\n\n");
                }
            }
        }
    }

    // ==== HashTree traversal ====

    private static void processHashTree(Element hashTree,
                                        BufferedWriter out,
                                        boolean insideTransaction,
                                        File scriptDir) throws Exception {

        Node node = hashTree.getFirstChild();
        while (node != null) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                String tag = el.getTagName();

                if ("HTTPSamplerProxy".equals(tag)) {
                    generateSamplerCode(el, out, insideTransaction, scriptDir);

                } else if ("TransactionController".equals(tag)) {
                    String txnName = el.getAttribute("testname");
                    if (txnName == null || txnName.trim().isEmpty()) {
                        txnName = "Txn_" + System.currentTimeMillis();
                    }
                    String lrTxnName = escapeForC(txnName);

                    out.write("    lr_start_transaction(\"" + lrTxnName + "\");\n\n");

                    Element txnTree = findFollowingHashTree(el);
                    if (txnTree != null) {
                        processHashTree(txnTree, out, true, scriptDir);
                        node = txnTree;
                    } else {
                        log(scriptDir, "WARNING: TransactionController '" + txnName + "' has no hashTree.");
                    }

                    out.write("    lr_end_transaction(\"" + lrTxnName + "\", LR_AUTO);\n\n");
                }
            }

            node = node.getNextSibling();
        }
    }

    // ==== Sampler + correlation ====

    private static void generateSamplerCode(Element sampler,
                                            BufferedWriter out,
                                            boolean insideTransaction,
                                            File scriptDir) throws Exception {
        String name = sampler.getAttribute("testname");
        if (name == null || name.trim().isEmpty()) {
            name = "Request_" + System.currentTimeMillis();
        }

        String method = getStringProp(sampler, "HTTPSampler.method");
        if (method == null || method.trim().isEmpty()) {
            method = "GET";
        }

        String baseUrl = buildBaseUrl(sampler);
        List<HttpArgument> args = extractHttpArguments(sampler);
        boolean postBodyRaw = isPostBodyRaw(sampler);

        String lrName = escapeForC(name);
        String baseUrlLr = escapeForC(convertJmeterVarsToLoadRunner(baseUrl));

        // Regex Extractors -> web_reg_save_param_ex
        List<RegexCorrelation> regexCorrs = extractRegexExtractors(sampler);
        for (RegexCorrelation rc : regexCorrs) {
            String paramEsc = escapeForC(rc.paramName);
            String regexEsc = escapeForC(convertJmeterVarsToLoadRunner(rc.regex));

            out.write("    web_reg_save_param_ex(\n");
            out.write("        \"ParamName=" + paramEsc + "\",\n");
            out.write("        \"RegExp=" + regexEsc + "\",\n");
            out.write("        LAST);\n\n");
        }

        // JSON Extractors -> web_reg_save_param_json
        List<JsonCorrelation> jsonCorrs = extractJsonExtractors(sampler);
        for (JsonCorrelation jc : jsonCorrs) {
            String paramEsc = escapeForC(jc.paramName);
            String queryEsc = escapeForC(convertJmeterVarsToLoadRunner(jc.jsonPathExpr));

            out.write("    web_reg_save_param_json(\n");
            out.write("        \"ParamName=" + paramEsc + "\",\n");
            out.write("        \"QueryString=" + queryEsc + "\",\n");
            out.write("        LAST);\n\n");
        }

        if (!insideTransaction) {
            out.write("    lr_start_transaction(\"" + lrName + "\");\n\n");
        }

        if ("GET".equalsIgnoreCase(method)) {
            String fullUrl = appendQueryString(baseUrl, args);
            fullUrl = escapeForC(convertJmeterVarsToLoadRunner(fullUrl));

            out.write("    web_url(\"" + lrName + "\",\n");
            out.write("        \"URL=" + fullUrl + "\",\n");
            out.write("        \"TargetFrame=\",\n");
            out.write("        \"Resource=0\",\n");
            out.write("        \"Mode=HTTP\",\n");
            out.write("        LAST);\n\n");

        } else {
            if (postBodyRaw) {
                String body = "";
                if (!args.isEmpty()) {
                    body = args.get(0).value;
                }
                body = escapeForC(convertJmeterVarsToLoadRunner(body));

                out.write("    web_custom_request(\"" + lrName + "\",\n");
                out.write("        \"URL=" + baseUrlLr + "\",\n");
                out.write("        \"Method=" + method + "\",\n");
                out.write("        \"Resource=0\",\n");
                out.write("        \"Mode=HTTP\",\n");
                out.write("        \"Body=" + body + "\",\n");
                out.write("        LAST);\n\n");

            } else if (!args.isEmpty()) {
                out.write("    web_submit_data(\"" + lrName + "\",\n");
                out.write("        \"Action=" + baseUrlLr + "\",\n");
                out.write("        \"Method=" + method + "\",\n");
                out.write("        \"TargetFrame=\",\n");
                out.write("        \"Resource=0\",\n");
                out.write("        \"Mode=HTTP\",\n");
                out.write("        ITEMDATA,\n");

                for (int i = 0; i < args.size(); i++) {
                    HttpArgument arg = args.get(i);
                    String paramName = escapeForC(convertJmeterVarsToLoadRunner(arg.name));
                    String paramValue = escapeForC(convertJmeterVarsToLoadRunner(arg.value));
                    out.write("        \"Name=" + paramName + "\", \"Value=" + paramValue + "\", ENDITEM,\n");
                }

                out.write("        LAST);\n\n");
            } else {
                out.write("    web_custom_request(\"" + lrName + "\",\n");
                out.write("        \"URL=" + baseUrlLr + "\",\n");
                out.write("        \"Method=" + method + "\",\n");
                out.write("        \"Resource=0\",\n");
                out.write("        \"Mode=HTTP\",\n");
                out.write("        LAST);\n\n");
            }
        }

        if (!insideTransaction) {
            out.write("    lr_end_transaction(\"" + lrName + "\", LR_AUTO);\n\n");
        }
    }

    // ==== HTTP arguments ====

    private static class HttpArgument {
        String name;
        String value;

        HttpArgument(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private static List<HttpArgument> extractHttpArguments(Element sampler) {
        List<HttpArgument> result = new ArrayList<>();

        NodeList children = sampler.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;

            if ("elementProp".equals(el.getTagName())
                    && "HTTPsampler.Arguments".equals(el.getAttribute("name"))
                    && "Arguments".equals(el.getAttribute("elementType"))) {

                NodeList argChildren = el.getChildNodes();
                for (int j = 0; j < argChildren.getLength(); j++) {
                    Node n2 = argChildren.item(j);
                    if (n2.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element col = (Element) n2;
                    if (!"collectionProp".equals(col.getTagName())) continue;
                    if (!"Arguments.arguments".equals(col.getAttribute("name"))) continue;

                    NodeList argNodes = col.getChildNodes();
                    for (int k = 0; k < argNodes.getLength(); k++) {
                        Node n3 = argNodes.item(k);
                        if (n3.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element argEl = (Element) n3;
                        if (!"elementProp".equals(argEl.getTagName())) continue;
                        if (!"HTTPArgument".equals(argEl.getAttribute("elementType"))) continue;

                        String argName = getStringProp(argEl, "Argument.name");
                        String argValue = getStringProp(argEl, "Argument.value");

                        if (argName != null || argValue != null) {
                            result.add(new HttpArgument(
                                    argName != null ? argName : "",
                                    argValue != null ? argValue : ""
                            ));
                        }
                    }
                }
            }
        }

        return result;
    }

    private static boolean isPostBodyRaw(Element sampler) {
        NodeList children = sampler.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("boolProp".equals(el.getTagName())
                    && "HTTPSampler.postBodyRaw".equals(el.getAttribute("name"))) {
                String v = el.getTextContent();
                return "true".equalsIgnoreCase(v.trim());
            }
        }
        return false;
    }

    private static String appendQueryString(String baseUrl, List<HttpArgument> args) {
        if (args == null || args.isEmpty()) {
            return baseUrl;
        }
        StringBuilder sb = new StringBuilder(baseUrl);
        if (!baseUrl.contains("?")) {
            sb.append("?");
        } else if (!baseUrl.endsWith("&") && !baseUrl.endsWith("?")) {
            sb.append("&");
        }

        for (int i = 0; i < args.size(); i++) {
            HttpArgument arg = args.get(i);
            String n = arg.name != null ? arg.name : "";
            String v = arg.value != null ? arg.value : "";
            sb.append(convertJmeterVarsToLoadRunner(n))
              .append("=")
              .append(convertJmeterVarsToLoadRunner(v));
            if (i < args.size() - 1) {
                sb.append("&");
            }
        }

        return sb.toString();
    }

    // ==== Correlation: Regex & JSON ====

    private static class RegexCorrelation {
        String paramName;
        String regex;
    }

    private static class JsonCorrelation {
        String paramName;
        String jsonPathExpr;
    }

    private static List<RegexCorrelation> extractRegexExtractors(Element sampler) {
        List<RegexCorrelation> result = new ArrayList<>();

        Element samplerTree = findFollowingHashTree(sampler);
        if (samplerTree == null) {
            return result;
        }

        Node node = samplerTree.getFirstChild();
        while (node != null) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                if ("RegexExtractor".equals(el.getTagName())) {
                    RegexCorrelation rc = new RegexCorrelation();
                    rc.paramName = getStringProp(el, "RegexExtractor.refname");
                    rc.regex = getStringProp(el, "RegexExtractor.regex");
                    if (rc.paramName != null && rc.regex != null &&
                            !rc.paramName.trim().isEmpty() && !rc.regex.trim().isEmpty()) {
                        result.add(rc);
                    }
                }
            }
            node = node.getNextSibling();
        }

        return result;
    }

    private static List<JsonCorrelation> extractJsonExtractors(Element sampler) {
        List<JsonCorrelation> result = new ArrayList<>();

        Element samplerTree = findFollowingHashTree(sampler);
        if (samplerTree == null) {
            return result;
        }

        Node node = samplerTree.getFirstChild();
        while (node != null) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                if ("JSONPostProcessor".equals(el.getTagName())) {
                    JsonCorrelation jc = new JsonCorrelation();
                    jc.paramName = getStringProp(el, "JSONPostProcessor.referenceName");
                    jc.jsonPathExpr = getStringProp(el, "JSONPostProcessor.jsonPathExpr");
                    if (jc.paramName != null && jc.jsonPathExpr != null &&
                            !jc.paramName.trim().isEmpty() && !jc.jsonPathExpr.trim().isEmpty()) {
                        result.add(jc);
                    }
                }
            }
            node = node.getNextSibling();
        }

        return result;
    }

    // ==== CSV + DAT ====

    private static class CsvParameterSet {
        String fileName;     // original CSV file name (in script dir)
        String datFileName;  // generated .dat file name (in script dir)
        List<String> variableNames;
        String delimiter;
    }

    private static List<CsvParameterSet> parseAndCopyCsvDataSets(Document doc,
                                                                 File jmxDir,
                                                                 File scriptDir) {
        List<CsvParameterSet> result = new ArrayList<>();

        NodeList csvNodes = doc.getElementsByTagName("CSVDataSet");
        for (int i = 0; i < csvNodes.getLength(); i++) {
            Element csv = (Element) csvNodes.item(i);

            String filename = getStringProp(csv, "filename");
            String variableNames = getStringProp(csv, "variableNames");
            String delimiter = getStringProp(csv, "delimiter");

            if (filename == null || filename.trim().isEmpty()) {
                continue;
            }

            File src = new File(filename);
            if (!src.isAbsolute()) {
                src = new File(jmxDir, filename);
            }
            if (!src.exists()) {
                System.out.println("CSV file not found: " + src.getAbsolutePath());
                continue;
            }

            // Copy CSV
            File destCsv = new File(scriptDir, src.getName());
            try {
                Files.copy(src.toPath(), destCsv.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            // Create .dat file with same content
            String baseName = destCsv.getName();
            int dot = baseName.lastIndexOf('.');
            String datName = (dot > 0 ? baseName.substring(0, dot) : baseName) + ".dat";
            File destDat = new File(scriptDir, datName);
            try {
                Files.copy(destCsv.toPath(), destDat.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            CsvParameterSet set = new CsvParameterSet();
            set.fileName = destCsv.getName();
            set.datFileName = destDat.getName();
            set.delimiter = delimiter != null ? delimiter : ",";
            set.variableNames = new ArrayList<>();

            if (variableNames != null && !variableNames.trim().isEmpty()) {
                String[] vars = variableNames.split(",");
                for (String v : vars) {
                    if (!v.trim().isEmpty()) {
                        set.variableNames.add(v.trim());
                    }
                }
            }

            result.add(set);
        }

        return result;
    }

    // ==== XML helpers ====

    private static String getStringProp(Element parent, String nameAttr) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("stringProp".equals(el.getTagName())
                    && nameAttr.equals(el.getAttribute("name"))) {
                return el.getTextContent();
            }
        }
        return null;
    }

    private static Element findFollowingHashTree(Element elem) {
        Node sib = elem.getNextSibling();
        while (sib != null) {
            if (sib.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) sib;
                if ("hashTree".equals(el.getTagName())) {
                    return el;
                }
            }
            sib = sib.getNextSibling();
        }
        return null;
    }

    private static String buildBaseUrl(Element sampler) {
        String domain = getStringProp(sampler, "HTTPSampler.domain");
        String protocol = getStringProp(sampler, "HTTPSampler.protocol");
        String port = getStringProp(sampler, "HTTPSampler.port");
        String path = getStringProp(sampler, "HTTPSampler.path");

        if (protocol == null || protocol.trim().isEmpty()) {
            protocol = "http";
        }
        if (domain == null || domain.trim().isEmpty()) {
            domain = "localhost";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://").append(domain);
        if (port != null && !port.trim().isEmpty()) {
            sb.append(":").append(port);
        }
        if (path != null && !path.trim().isEmpty()) {
            if (!path.startsWith("/")) sb.append("/");
            sb.append(path);
        }
        return sb.toString();
    }

    // ==== Logging & utils ====

    private static void writeConversionLog(File scriptDir,
                                           String tgName,
                                           List<CsvParameterSet> csvParams) throws Exception {
        File logFile = new File(scriptDir, "conversion.log");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8))) {

            out.write("ThreadGroup: " + tgName + "\n");
            out.write("Script folder: " + scriptDir.getAbsolutePath() + "\n\n");
            out.write("CSV/DAT Parameters:\n");
            if (csvParams.isEmpty()) {
                out.write("  (none)\n");
            } else {
                for (CsvParameterSet set : csvParams) {
                    out.write("  CSV: " + set.fileName + "  DAT: " + set.datFileName
                            + "  Vars: " + set.variableNames + "\n");
                }
            }
            out.write("\nNotes:\n");
            out.write("  - Correlations (Regex, JSON) have been converted to web_reg_save_param_ex/web_reg_save_param_json.\n");
            out.write("  - Parameters reference .dat files in default.cfg and parameters.prm.\n");
            out.write("  - Please open this script in VuGen, check parameters & correlations.\n");
            out.write("  - Plugin by SreeBommakanti.\n");
        }
    }

    private static void log(File scriptDir, String message) {
        System.out.println("[JMX->LR] " + message);
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static String escapeForC(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /** Convert JMeter-style ${var} to LoadRunner-style {var} */
    private static String convertJmeterVarsToLoadRunner(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("\\$\\{([^}]+)}", "\\{$1}");
    }
}
