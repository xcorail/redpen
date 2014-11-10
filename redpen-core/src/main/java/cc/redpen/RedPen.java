/**
 * redpen: a text inspection tool
 * Copyright (C) 2014 Recruit Technologies Co., Ltd. and contributors
 * (see CONTRIBUTORS.md)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.redpen;

import cc.redpen.config.Configuration;
import cc.redpen.config.SymbolTable;
import cc.redpen.config.ValidatorConfiguration;
import cc.redpen.distributor.DefaultResultDistributor;
import cc.redpen.distributor.ResultDistributor;
import cc.redpen.model.*;
import cc.redpen.parser.DocumentParser;
import cc.redpen.parser.SentenceExtractor;
import cc.redpen.symbol.DefaultSymbols;
import cc.redpen.validator.PreProcessor;
import cc.redpen.validator.ValidationError;
import cc.redpen.validator.Validator;
import cc.redpen.validator.ValidatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validate all input files using appended Validators.
 */
public class RedPen {
    private static final Logger LOG = LoggerFactory.getLogger(RedPen.class);

    private final List<Validator<Document>> documentValidators = new ArrayList<>();
    private final List<Validator<Section>> sectionValidators = new ArrayList<>();
    private final List<Validator<Sentence>> sentenceValidators = new ArrayList<>();
    private final ResultDistributor distributor;
    private final Configuration configuration;
    private final SentenceExtractor sentenceExtractor;

    private RedPen(Configuration configuration, ResultDistributor distributor) throws RedPenException {
        this.configuration = configuration;
        this.distributor = distributor;
        this.sentenceExtractor = getSentenceExtractor(this.configuration);
        loadValidators();
    }

    static Type getParameterizedClass(Object obj) {
        if (obj == null) {
            return null;
        }

        Class clazz = obj.getClass();
        Type genericInterface = clazz.getGenericSuperclass();
        ParameterizedType parameterizedType;
        try {
            parameterizedType =
                    ParameterizedType.class.cast(genericInterface);
        } catch (ClassCastException e) {
            return null;
        }

        if (parameterizedType.getActualTypeArguments().length == 0) {
            return null;
        }
        return parameterizedType.getActualTypeArguments()[0];
    }

    public Configuration getConfiguration() {
        return this.configuration;
    }

    /**
     * Load validators written in the configuration file.
     */
    @SuppressWarnings("unchecked")
    private void loadValidators()
            throws RedPenException {
        if (configuration == null) {
            throw new IllegalStateException("Configuration object is null");
        }

        for (ValidatorConfiguration config : configuration.getValidatorConfigs()) {

            Validator<?> validator = ValidatorFactory.getInstance(
                    config, configuration.getSymbolTable());
            Type type = getParameterizedClass(validator);

            if (type == Sentence.class) {
                this.sentenceValidators.add((Validator<Sentence>) validator);
            } else if (type == Section.class) {
                this.sectionValidators.add((Validator<Section>) validator);
            } else if (type == Document.class) {
                this.documentValidators.add((Validator<Document>) validator);
            } else {
                throw new IllegalStateException("No validator for " + type + " block.");
            }
        }
    }

    /**
     * Validate the input document collection.
     *
     * @param documentCollection input document collection generated by Parser
     * @return list of validation errors
     */
    public Map<Document, List<ValidationError>> validate(DocumentCollection documentCollection) {
        distributor.flushHeader();
        Map<Document, List<ValidationError>> docErrorsMap = new HashMap<>();
        documentCollection.forEach(e -> docErrorsMap.put(e, new ArrayList<>()));
        runDocumentValidators(documentCollection, docErrorsMap);
        runSectionValidators(documentCollection, docErrorsMap);
        runSentenceValidators(documentCollection, docErrorsMap);
        distributor.flushFooter();
        return docErrorsMap;
    }

    private void runDocumentValidators(
            DocumentCollection documentCollection,
            Map<Document, List<ValidationError>> docErrorsMap) {
        for (Document document : documentCollection) {
            List<ValidationError> newErrors = validateDocument(document);
            for (ValidationError error : newErrors) {
                flushError(document, error);
            }
            List<ValidationError> validationErrors = docErrorsMap.get(document);
            validationErrors.addAll(newErrors);
        }
    }

    private void flushError(Document document, ValidationError error) {
        /**
         * When the flush of input error is failed, the output process continues skipping the failed error.
         */
        try {
            distributor.flushError(document, error);
        } catch (RedPenException e) {
            LOG.error("Failed to flush error: " + error.toString());
            LOG.error("Skipping to flush this error...");
        }
    }

    private void runSectionValidators(
            DocumentCollection documentCollection,
            Map<Document, List<ValidationError>> docErrorsMap) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                List<ValidationError> newErrors = validateSection(section);
                for (ValidationError error : newErrors) {
                    flushError(document, error);
                }
                List<ValidationError> validationErrors = docErrorsMap.get(document);
                validationErrors.addAll(newErrors);
            }
        }
    }

    private void runSentenceValidators(
            DocumentCollection documentCollection,
            Map<Document, List<ValidationError>> docErrorsMap) {
        runSentencePreProcessorsToDocumentCollection(documentCollection);
        runSentenceValidatorsToDocumentCollection(documentCollection, docErrorsMap);
    }

    private void runSentencePreProcessorsToDocumentCollection(
            DocumentCollection documentCollection) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                applySentencePreProcessorsToSection(section);
            }
        }
    }

    private void applySentencePreProcessorsToSection(Section section) {
        // apply paragraphs
        for (Paragraph paragraph : section.getParagraphs()) {
            preprocessSentences(paragraph.getSentences());
        }
        // apply to section header
        preprocessSentences(section.getHeaderContents());
        // apply to lists
        for (ListBlock listBlock : section.getListBlocks()) {
            for (ListElement listElement : listBlock.getListElements()) {
                preprocessSentences(listElement.getSentences());
            }
        }
    }

    private void preprocessSentences(List<Sentence> sentences) {
        for (Validator<Sentence> sentenceValidator : sentenceValidators) {
            if (sentenceValidator instanceof PreProcessor) {
                PreProcessor<Sentence> preprocessor = (PreProcessor<Sentence>) sentenceValidator;
                sentences.forEach(preprocessor::preprocess);
            }
        }
    }

    private void runSentenceValidatorsToDocumentCollection(
            DocumentCollection documentCollection, Map<Document, List<ValidationError>> docErrorsMap) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                List<ValidationError> newErrors =
                        applySentenceValidationsToSection(document, section);
                docErrorsMap.get(document).addAll(newErrors);
            }
        }
    }

    private List<ValidationError> applySentenceValidationsToSection(
            Document document, Section section) {
        List<ValidationError> newErrors = new ArrayList<>();
        // apply paragraphs
        for (Paragraph paragraph : section.getParagraphs()) {
            newErrors.addAll(validateParagraph(paragraph));
        }

        // apply to section header
        newErrors.addAll(validateSentences(section.getHeaderContents()));

        // apply to lists
        for (ListBlock listBlock : section.getListBlocks()) {
            for (ListElement listElement : listBlock.getListElements()) {
                newErrors.addAll(validateSentences(listElement.getSentences()));
            }
        }
        for (ValidationError error : newErrors) {
            flushError(document, error);
        }
        return newErrors;
    }

    private List<ValidationError> validateDocument(Document document) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Document> validator : documentValidators) {
            errors.addAll(validator.validate(document));
        }
        return errors;
    }

    private List<ValidationError> validateSection(Section section) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Section> sectionValidator : sectionValidators) {
            errors.addAll(sectionValidator.validate(section));
        }
        return errors;
    }

    private List<ValidationError> validateParagraph(Paragraph paragraph) {
        List<ValidationError> errors = new ArrayList<>();
        errors.addAll(validateSentences(paragraph.getSentences()));
        return errors;
    }

    private List<ValidationError> validateSentences(List<Sentence> sentences) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Sentence> sentenceValidator : sentenceValidators) {
            for (Sentence sentence : sentences) {
                errors.addAll(sentenceValidator.validate(sentence));
            }
        }
        return errors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RedPen redPen = (RedPen) o;

        if (distributor != null ? !distributor.equals(redPen.distributor) : redPen.distributor != null) return false;
        if (sectionValidators != null ? !sectionValidators.equals(redPen.sectionValidators) : redPen.sectionValidators != null)
            return false;
        if (sentenceValidators != null ? !sentenceValidators.equals(redPen.sentenceValidators) : redPen.sentenceValidators != null)
            return false;
        if (documentValidators != null ? !documentValidators.equals(redPen.documentValidators) : redPen.documentValidators != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = documentValidators != null ? documentValidators.hashCode() : 0;
        result = 31 * result + (sectionValidators != null ? sectionValidators.hashCode() : 0);
        result = 31 * result + (sentenceValidators != null ? sentenceValidators.hashCode() : 0);
        result = 31 * result + (distributor != null ? distributor.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RedPen{" +
                "documentValidators=" + documentValidators +
                ", sectionValidators=" + sectionValidators +
                ", sentenceValidators=" + sentenceValidators +
                ", distributor=" + distributor +
                '}';
    }

    /**
     * parses given inputstream
     *
     * @param parser      DocumentParser parser
     * @param InputStream content to parse
     * @return parsed document
     * @throws RedPenException
     */
    public Document parse(DocumentParser parser, InputStream InputStream) throws RedPenException {
        return parser.parse(InputStream, sentenceExtractor, configuration.getTokenizer());
    }

    /**
     * parses given content
     *
     * @param parser  DocumentParser parser
     * @param content content to parse
     * @return parsed document
     * @throws RedPenException
     */
    public Document parse(DocumentParser parser, String content) throws RedPenException {
        return parser.parse(content, sentenceExtractor, configuration.getTokenizer());
    }

    /**
     * parses given files
     *
     * @param parser DocumentParser parser
     * @param files  files to parse
     * @return parsed documents
     * @throws RedPenException
     */
    public DocumentCollection parse(DocumentParser parser, File[] files) throws RedPenException {
        DocumentCollection.Builder documentBuilder =
                new DocumentCollection.Builder();
        for (File file : files) {
            documentBuilder.addDocument(parser.parse(file, sentenceExtractor, configuration.getTokenizer()));
        }
        // @TODO extract summary information to validate documentCollection effectively
        return documentBuilder.build();
    }

    static SentenceExtractor getSentenceExtractor(Configuration configuration) {
        SymbolTable symbolTable = configuration.getSymbolTable();
        List<String> periods = extractPeriods(symbolTable);
        List<String> rightQuotations = extractRightQuotations(symbolTable);
        return new SentenceExtractor(periods, rightQuotations);
    }

    private static List<String> extractRightQuotations(SymbolTable symbolTable) {
        List<String> rightQuotations = new ArrayList<>();
        if (symbolTable.containsSymbol("RIGHT_SINGLE_QUOTATION_MARK")) {
            rightQuotations.add(
                    symbolTable.getSymbol("RIGHT_SINGLE_QUOTATION_MARK").getValue());
        } else {
            rightQuotations.add(
                    DefaultSymbols.getInstance().get("RIGHT_SINGLE_QUOTATION_MARK").getValue());
        }
        if (symbolTable.containsSymbol("RIGHT_DOUBLE_QUOTATION_MARK")) {
            rightQuotations.add(
                    symbolTable.getSymbol("RIGHT_DOUBLE_QUOTATION_MARK").getValue());
        } else {
            rightQuotations.add(
                    DefaultSymbols.getInstance().get("RIGHT_DOUBLE_QUOTATION_MARK").getValue());
        }
        for (String rightQuotation : rightQuotations) {
            LOG.info("\"" + rightQuotation + "\" is added as a end of right quotation character.");
        }
        return rightQuotations;
    }

    private static List<String> extractPeriods(SymbolTable symbolTable) {
        List<String> periods = new ArrayList<>();
        if (symbolTable.containsSymbol("FULL_STOP")) {
            periods.add(
                    symbolTable.getSymbol("FULL_STOP").getValue());
        } else {
            periods.add(
                    DefaultSymbols.getInstance().get("FULL_STOP").getValue());
        }

        if (symbolTable.containsSymbol("QUESTION_MARK")) {
            periods.add(
                    symbolTable.getSymbol("QUESTION_MARK").getValue());
        } else {
            periods.add(
                    DefaultSymbols.getInstance().get("QUESTION_MARK").getValue());
        }

        if (symbolTable.containsSymbol("EXCLAMATION_MARK")) {
            periods.add(
                    symbolTable.getSymbol("EXCLAMATION_MARK").getValue());
        } else {
            periods.add(
                    DefaultSymbols.getInstance().get("EXCLAMATION_MARK").getValue());
        }

        for (String period : periods) {
            LOG.info("\"" + period + "\" is added as a end of sentence character");
        }
        return periods;
    }

    /**
     * Builder for {@link cc.redpen.RedPen}.
     */
    public static class Builder {

        private Configuration configuration;

        private ResultDistributor distributor = new DefaultResultDistributor(
                new PrintStream(System.out)
        );

        public Builder setConfiguration(Configuration configuration) {
            this.configuration = configuration;
            return this;
        }

        public Builder setConfigPath(String configPath) throws RedPenException {
            ConfigurationLoader configLoader = new ConfigurationLoader();
            InputStream inputConfigStream = RedPen.class.getResourceAsStream(configPath);

            if (inputConfigStream == null) {
                LOG.info("Loading config from specified config file: \"{}\"", configPath);
                configuration = configLoader.loadConfiguration(configPath);
            } else {
                LOG.info("Loading config from default configuration");
                configuration = configLoader.loadConfiguration(inputConfigStream);
            }
            return this;
        }

        public Builder setResultDistributor(ResultDistributor distributor) {
            this.distributor = distributor;
            return this;
        }

        public RedPen build() throws RedPenException {
            if (configuration == null) {
                throw new IllegalStateException("Configuration not set.");
            }
            return new RedPen(configuration, distributor);
        }
    }
}
