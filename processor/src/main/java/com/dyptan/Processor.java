package com.dyptan;

import com.google.gson.Gson;
import com.ria.avro.Advertisement;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.avro.coders.AvroCoder;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.io.kafka.KafkaRecord;
import org.apache.beam.sdk.io.mongodb.MongoDbIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Properties;

public class Processor implements Serializable {
    static Logger logger = LoggerFactory.getLogger("MessageProcessor");


    public static void main(String[] args) throws IOException {
        var properties = new Properties();
        properties.load(new FileInputStream("application.properties"));

//        ServerBuilder.forPort(50051)
//                .addService(new ProcessorServiceImpl())
//                .build()
//                .start();
        logger.info("Starting api server...");
        PipelineOptions options = PipelineOptionsFactory.create();
        Pipeline pipeline = Pipeline.create(options);

        @SuppressWarnings("unchecked")
        PTransform<PBegin, PCollection<KafkaRecord<Integer, Advertisement>>> read = KafkaIO.<Integer, Advertisement>read()
                .withBootstrapServers(properties.getProperty("kafka.bootstrap.servers"))
                .withTopic(properties.getProperty("kafka.producer.topic"))
                .withConsumerConfigUpdates(Collections.singletonMap("specific.avro.reader", "true"))
                .withConsumerConfigUpdates(Collections.singletonMap("fetch.max.wait.ms", "5000"))
                .withConsumerConfigUpdates(Collections.singletonMap("auto.offset.reset", "latest"))
                .withConsumerConfigUpdates(Collections.singletonMap("schema.registry.url", properties.getProperty("schema.registry.url")))
                .withKeyDeserializer(IntegerDeserializer.class)
                .withValueDeserializerAndCoder((Class) KafkaAvroDeserializer.class, AvroCoder.of(Advertisement.class));


        PCollection<KV<Integer, Advertisement>> advertisementRecords =
                pipeline.apply(read)
                        .apply("ExtractRecord", ParDo.of(
                                new DoFn<KafkaRecord<Integer, Advertisement>, KV<Integer, Advertisement>>() {
                                    @ProcessElement
                                    public void processElement(ProcessContext c) {
                                        KafkaRecord<Integer, Advertisement> record = c.element();
                                        KV<Integer, Advertisement> log = record.getKV();
                                        logger.debug("Key Obtained: " + log.getKey());
                                        logger.debug("Value Obtained: " + log.getValue().toString());
                                        c.output(record.getKV());

                                    }
                                }));

        PCollection<Document> mongoDocumentCollection = advertisementRecords
                .apply("TransformToDocument", ParDo.of(new KVToMongoDocumentFn()));

        String mongoURI = properties.getProperty("mongo.uri");
        String databaseName = properties.getProperty("database.name");
        String collectionName = properties.getProperty("collection.name");

        mongoDocumentCollection.apply(
                MongoDbIO.write()
                        .withUri(mongoURI)
                        .withDatabase(databaseName)
                        .withCollection(collectionName)

        );

        logger.info("Starting processing pipeline...");
        pipeline.run().waitUntilFinish();
    }


    public static class KVToMongoDocumentFn extends DoFn<KV<Integer, Advertisement>, Document> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            KV<Integer, Advertisement> input = c.element();
            Document document = Document.parse(new Gson().toJson(input.getValue(), Advertisement.class));
            document.put("_id", new ObjectId(String.format("%024d", input.getKey())));
            c.output(document);
        }
    }
}
