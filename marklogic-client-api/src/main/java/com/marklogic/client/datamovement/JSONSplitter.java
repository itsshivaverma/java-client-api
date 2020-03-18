/*
 * Copyright 2020 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.marklogic.client.datamovement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.impl.DocumentWriteOperationImpl;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.io.marker.AbstractWriteHandle;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The JSONSplitter is used to split large JSON file into separate payloads for writing to the database. The JSON file
 * is typically an array containing an object for each record. JSONSplitter could split each targeted object or array
 * into separate files.
 * @param <T> The type of the handle used for each split payload
 */
public class JSONSplitter<T extends AbstractWriteHandle> implements Splitter<T> {

    /**
     * Construct a simple JSONSplitter which split objects or arrays under an array.
     * @return a JSONSplitter class which splits each object or array into a separate payload
     */
    static public JSONSplitter<StringHandle> makeArraySplitter() {

        JSONSplitter.ArrayVisitor arrayVisitor = new JSONSplitter.ArrayVisitor();
        return new JSONSplitter<>(arrayVisitor);
    }

    private JSONSplitter.Visitor<T> visitor;
    private int count = 0;

    /**
     * Construct a JSONSplitter which splits the JSON file according to the visitor.
     * @param visitor describes how to spit the file
     */
    public JSONSplitter(JSONSplitter.Visitor<T> visitor) {
        setVisitor(visitor);
    }

    /**
     * Get the visitor used in JSONSplitter class.
     * @return the visitor used in JSONSplitter class
     */
    public JSONSplitter.Visitor<T> getVisitor() {
        return this.visitor;
    }

    /**
     * Set the visitor to select objects or arrays to split in JSONSplitter.
     * @param visitor the visitor describes the rule to split the JSON file
     */
    public void setVisitor(JSONSplitter.Visitor<T> visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("Visitor cannot be null");
        }
        this.visitor = visitor;
    }

    @Override
    public long getCount() {
        return count;
    }

    /**
     * Takes an InputStream of a JSON file and split it into a steam of handles.
     * @param input is the incoming InputStream of a JSON file.
     * @return a stream of handles to write to database
     * @throws IOException
     */
    @Override
    public Stream<T> split(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        JsonParser jsonParser = new JsonFactory().createParser(input);
        return split(jsonParser);
    }

    /**
     * Take an input of JsonParser created from the JSON file and split it into a stream of handles to write to database.
     * @param input JsonParser created from the JSON file
     * @return a stream of handles to write to database
     * @throws IOException
     */
    public Stream<T> split(JsonParser input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        JSONSplitter.HandleSpliterator<T> handleSpliterator = new JSONSplitter.HandleSpliterator<>(this, input);
        return StreamSupport.stream(handleSpliterator, true);
    }

    /**
     * Take an input of JsonParser created from the JSON file and split it into a stream of DocumentWriteOperations
     * to write to database.
     * @param input JsonParser created from the JSON file
     * @return a stream of DocumentWriteOperation to write to database
     */
    public Stream<DocumentWriteOperation> splitWriteOperations(JsonParser input) {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        JSONSplitter.DocumentWriteOperationSpliterator spliterator =
                new JSONSplitter.DocumentWriteOperationSpliterator<>(this, input);
        return StreamSupport.stream(spliterator, true);
    }

    /**
     * The Visitor class is used to describe the rule of how to split the JSON file.
     * It checks if the current object or array is the target to split.
     * If it is, convert the target object or array into buffered handles or DocumentWriteOperations.
     * @param <T> The type of the handle used for each split
     */
    static public abstract class Visitor<T extends AbstractWriteHandle>   {

        public int arrayDepth = 0;

        /**
         * Use arrayDepth and containerKey to check if the current object is the one to split.
         * @param containerKey The key of the object which the value contains current object
         * @return different operations to either process current object, go down the JSON tree or skip current object
         */
        public NodeOperation startObject(String containerKey) {
            if (arrayDepth > 0) {
                return NodeOperation.PROCESS;
            }

            return NodeOperation.DESCEND;
        }

        /**
         * Receives a notification when hitting the end of current object.
         * @param containerKey The key of the object which the value contains current object
         */
        public void endObject(String containerKey) {
        }

        /**
         * Use the arrayDepth and containerKey to check if the current array is the one to split. Also increase arrayDepth.
         * @param containerKey The key of the object which the value contains current array
         * @return different operations to either process current array, go down the JSON tree or skip current array
         */
        public NodeOperation startArray(String containerKey) {
            arrayDepth++;

            if (arrayDepth > 1) {
                arrayDepth--;
                return NodeOperation.PROCESS;
            }

            return NodeOperation.DESCEND;
        }

        /**
         * Receives a notification when hitting end of array.
         * @param containerKey The key of the object which the value contains current array
         */
        public void endArray(String containerKey) {
        }

        /**
         * Construct buffered content handles with proper types from JsonParser.
         * @param containerParser the JsonParser with target object or array
         * @return the handle with target object or array as content
         */
        public abstract T makeBufferedHandle(JsonParser containerParser);

        /**
         * Construct buffered DocumentWriteOperations from the handle which contains target content
         * @param handle the handle contains target object or array
         * @return DocumentWriteOperations to write to database
         */
        public DocumentWriteOperation makeDocumentWriteOperation(T handle) {
            if (handle == null) {
                throw new IllegalArgumentException("Handle cannot be null");
            }

            String uri = UUID.randomUUID().toString() + ".json";

            return new DocumentWriteOperationImpl(
                    DocumentWriteOperation.OperationType.DOCUMENT_WRITE,
                    uri,
                    null,
                    handle
            );
        }

        /**
         * Serialize the target object or array in JsonParser to Strings.
         * @param containerParser the JsonParser with target object or array
         * @return Serialized string containing target object or array
         */
        public String serialize(JsonParser containerParser) {
            if (containerParser == null) {
                throw new IllegalArgumentException("JsonParser cannot be null");
            }

            try {
                StringWriter stringWriter = new StringWriter();
                JsonGenerator jsonGenerator = new JsonFactory().createGenerator(stringWriter);
                jsonGenerator.copyCurrentStructure(containerParser);
                jsonGenerator.close();
                return stringWriter.toString();
            } catch (IOException e) {
                throw new RuntimeException("Could not serialize the document", e);
            }
        }
    }

    /**
     * The basic visitor only splits objects or arrays under top array.
     */
    static public class ArrayVisitor extends Visitor<StringHandle>   {

        private int arrayDepth = 0;

        /**
         * Use the arrayDepth and containerKey to check if the current object is the target to split.
         * @param containerKey The key of the object which the value contains current object
         * @return different operations to either process current object or go down the JSON tree
         */
        public NodeOperation startObject(String containerKey) {
            if (arrayDepth > 0) {
                return NodeOperation.PROCESS;
            }

            return NodeOperation.DESCEND;
        }

        /**
         * Use the arrayDepth and containerKey to check if the current array is the target to split.
         * Also increase arrayDepth.
         * @param containerKey The key of the object which the value contains current array
         * @return different operations to either process current array or go down the JSON tree
         */
        public NodeOperation startArray(String containerKey) {
            arrayDepth++;

            if (arrayDepth > 1) {
                arrayDepth--;
                return NodeOperation.PROCESS;
            }

            return NodeOperation.DESCEND;
        }

        /**
         * Receives a notification when hitting end of array, and decreases arrayDepth.
         * @param containerKey The key of the object which the value contains current array
         */
        public void endArray(String containerKey) {
            arrayDepth--;
        }

        /**
         * Construct buffered StringHandles from JsonParser.
         * @param containerParser the JsonParser with target object or array
         * @return the StringHandle with target object or array
         */
        public StringHandle makeBufferedHandle(JsonParser containerParser) {
            if (containerParser == null) {
                throw new IllegalArgumentException("JsonParser cannot be null");
            }
            String content = serialize(containerParser);
            return new StringHandle(content).withFormat(Format.JSON);
        }
    }

    private static abstract class JSONSpliterator<U, T extends AbstractWriteHandle> extends Spliterators.AbstractSpliterator<U> {

        private JsonParser jsonParser;

        private JsonParser getJsonParser() {
            return this.jsonParser;
        }

        private void setJsonParser(JsonParser jsonParser) {
            this.jsonParser = jsonParser;
        }

        private ArrayDeque<String> key = new ArrayDeque<>();
        private Visitor<T> visitor;
        private JSONSplitter<T> splitter;

        private void setSplitter(JSONSplitter<T> splitter) {
            if (splitter == null) {
                throw new IllegalArgumentException("JSONSplitter cannot be null");
            }
            this.splitter = splitter;
        }

        JSONSplitter<T> getSplitter() {
            return this.splitter;
        }

        JSONSpliterator(JSONSplitter<T> splitter, JsonParser jsonParser) {
            super(Long.MAX_VALUE, Spliterator.NONNULL + Spliterator.IMMUTABLE);
            setSplitter(splitter);
            setJsonParser(jsonParser);
            this.visitor = splitter.getVisitor();
        }

        T getNextHandle() {
            try {
                while (jsonParser.nextToken() != null) {
                    JsonToken currentToken = jsonParser.currentToken();

                    switch (currentToken) {
                        case FIELD_NAME:
                            key.pop();
                            key.push(jsonParser.getCurrentName());
                            break;

                        case START_OBJECT:
                            NodeOperation operation = visitor.startObject(key.peek());
                            switch (operation) {
                                case DESCEND:
                                    key.push(currentToken.asString());
                                    break;

                                case PROCESS:
                                    T handle = visitor.makeBufferedHandle(new JSONSplitter.JsonContainerParser(jsonParser));
                                    if (handle != null) {
                                        return handle;
                                    }
                                    break;

                                case SKIP:
                                    jsonParser.skipChildren();
                                    break;

                                default:
                                    throw new IllegalStateException("Unknown state");
                            }

                            break;

                        case END_OBJECT:
                            visitor.endObject(key.peek());
                            key.pop();
                            break;

                        case START_ARRAY:
                            NodeOperation operation_array = visitor.startArray(key.peek());
                            //For maintenance: this is similar switch statement as in case START_OBJECT.
                            switch (operation_array) {
                                case DESCEND:
                                    break;

                                case PROCESS:
                                    T handle = visitor.makeBufferedHandle(new JSONSplitter.JsonContainerParser(jsonParser));
                                    if (handle != null) {
                                        return handle;
                                    }
                                    break;

                                case SKIP:
                                    jsonParser.skipChildren();
                                    break;

                                default:
                                    throw new IllegalStateException("Unknown state");
                            }

                            break;

                        case END_ARRAY:
                            visitor.endArray(key.peek());
                            break;
                    }
                }

                return null;

            } catch (IOException e) {
                throw new RuntimeException("Failed to traverse document", e);
            }
        }

    }

    private static class HandleSpliterator<T extends AbstractWriteHandle> extends JSONSpliterator<T, T> {

        HandleSpliterator(JSONSplitter<T> splitter, JsonParser jsonParser) {
            super(splitter, jsonParser);
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            T handle = (T) getNextHandle();
            if (handle == null) {
                return false;
            }

            action.accept(handle);
            getSplitter().count++;

            return true;
        }
    }

    private static class DocumentWriteOperationSpliterator<T extends AbstractWriteHandle> extends JSONSpliterator<DocumentWriteOperation, T> {

        DocumentWriteOperationSpliterator(JSONSplitter<T> splitter, JsonParser jsonParser) {
            super(splitter, jsonParser);
        }

        @Override
        public boolean tryAdvance(Consumer<? super DocumentWriteOperation> action) {
            T handle = (T) getNextHandle();
            if (handle == null) {
                return false;
            }

            DocumentWriteOperation documentWriteOperation = getSplitter().getVisitor().makeDocumentWriteOperation(handle);

            action.accept(documentWriteOperation);
            getSplitter().count++;

            return true;
        }
    }

    private static class JsonContainerParser extends JsonParserDelegate {

        JsonContainerParser(JsonParser jsonParser) {
            super(jsonParser);
        }

        private int depth = 1;

        @Override
        public boolean isClosed() {
            return (depth > 0);
        }

        private void maintainDepth(JsonToken currentToken) {
            if (currentToken == JsonToken.START_ARRAY || currentToken == JsonToken.START_OBJECT) {
                depth++;
            }

            if (currentToken == JsonToken.END_ARRAY || currentToken == JsonToken.END_OBJECT) {
                depth--;
            }
        }

        private void maintainDepth() {
            maintainDepth(super.currentToken());
        }

        @Override
        public JsonToken nextToken() throws IOException {
            if (depth == 0) {
                throw new IllegalStateException("The JSON branch is closed");
            }

            JsonToken next = super.nextToken();
            maintainDepth(next);
            return next;
        }

        @Override
        public Boolean nextBooleanValue() throws IOException {
            if (depth == 0) {
                return null;
            }
            boolean next = super.nextBooleanValue();
            maintainDepth();
            return next;
        }

        @Override
        public String nextFieldName() throws IOException {
            if (depth == 0) {
                return null;
            }
            String next = super.nextFieldName();
            maintainDepth();
            return next;
        }

        @Override
        public int nextIntValue(int defaultValue) throws IOException {
            if (depth == 0) {
                return -1;
            }
            int next = super.nextIntValue(defaultValue);
            maintainDepth();
            return next;
        }

        @Override
        public long nextLongValue(long defaultValue) throws IOException {
            if (depth == 0) {
                return -1;
            }
            long next = super.nextLongValue(defaultValue);
            maintainDepth();
            return next;
        }

        @Override
        public String nextTextValue() throws IOException {
            if (depth == 0) {
                return null;
            }
            String next = super.nextTextValue();
            maintainDepth();
            return next;
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("Current JSON branch cannot be closed.");
        }
    }
}