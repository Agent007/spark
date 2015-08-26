/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.examples.streaming;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.twitter.TwitterUtils;
import scala.Tuple2;
import twitter4j.Status;

import java.util.Arrays;
import java.util.List;

/**
 * Inspired by TwitterPopularTags, this example program displays the most positive
 * hash tags by joining the streaming twitter data with a static RDD of
 * the word-sentiment file provided by http://alexdavies.net/twitter-sentiment-analysis/
 */
public class JavaTwitterTagSentiments {
    private JavaTwitterTagSentiments() {
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: JavaTwitterTagSentiments <consumer key> <consumer secret>" +
                    " <access token> <access token secret> [<filters>]");
            System.exit(1);
        }

        StreamingExamples.setStreamingLogLevels();

        String consumerKey = args[0];
        String consumerSecret = args[1];
        String accessToken = args[2];
        String accessTokenSecret = args[3];
        String[] filters = Arrays.copyOfRange(args, 4, args.length);

        System.setProperty("twitter4j.oauth.consumerKey", consumerKey);
        System.setProperty("twitter4j.oauth.consumerSecret", consumerSecret);
        System.setProperty("twitter4j.oauth.accessToken", accessToken);
        System.setProperty("twitter4j.oauth.accessTokenSecret", accessTokenSecret);

        SparkConf sparkConf = new SparkConf().setAppName("JavaTwitterTagSentiments");
        JavaStreamingContext jssc = new JavaStreamingContext(sparkConf, new Duration(2000));
        JavaReceiverInputDStream<Status> stream = TwitterUtils.createStream(jssc, filters);

        JavaDStream<String> words = stream.flatMap(new FlatMapFunction<Status, String>() {
            @Override
            public Iterable<String> call(Status s) {
                return Arrays.asList(s.getText().split(" "));
            }
        });

        JavaDStream<String> hashTags = words.filter(new Function<String, Boolean>() {
            @Override
            public Boolean call(String word) throws Exception {
                return word.startsWith("#");
            }
        });

        String wordSentimentFilePath = "examples/src/main/resources/twitter_sentiment_list.txt";
        final JavaPairRDD<String, Double> wordSentiments = jssc.sc().textFile(wordSentimentFilePath)
                .mapToPair(new PairFunction<String, String, Double>(){
                   @Override
                    public Tuple2<String, Double> call(String line) {
                       String[] columns = line.split(",");
                       return new Tuple2<String, Double>(columns[0], Double.parseDouble(columns[1]));
                   }
                });

        JavaPairDStream<String, Integer> hashTagCount = hashTags.mapToPair(
                new PairFunction<String, String, Integer>() {
                    @Override
                    public Tuple2<String, Integer> call(String s) {
                        // leave out the # character
                        return new Tuple2<String, Integer>(s.substring(1), 1);
                    }
                });

        JavaPairDStream<String, Integer> hashTagTotals = hashTagCount.reduceByKeyAndWindow(
                new Function2<Integer, Integer, Integer>() {
                    @Override
                    public Integer call(Integer a, Integer b) {
                        return a + b;
                    }
                }, new Duration(10000));

        JavaPairDStream<String, Tuple2<Double, Integer>> joinedTuples = hashTagTotals.transformToPair(
                new Function<JavaPairRDD<String, Integer>, JavaPairRDD<String,
                        Tuple2<Double, Integer>>>() {
                    @Override
                    public JavaPairRDD<String, Tuple2<Double, Integer>> call(JavaPairRDD<String,
                            Integer> topicCount)
                            throws Exception {
                        return wordSentiments.join(topicCount);
                    }
                });

        JavaPairDStream<String, Double> topicHappiness = joinedTuples.mapToPair(
                new PairFunction<Tuple2<String, Tuple2<Double, Integer>>, String, Double>() {
            @Override
            public Tuple2<String, Double> call(Tuple2<String,
                    Tuple2<Double, Integer>> topicAndTuplePair) throws Exception {
                Tuple2<Double, Integer> happinessAndCount = topicAndTuplePair._2();
                return new Tuple2<String, Double>(topicAndTuplePair._1(),
                        happinessAndCount._1() * happinessAndCount._2());
            }
        });

        JavaPairDStream<Double, String> happinessTopicPairs = topicHappiness.mapToPair(
                new PairFunction<Tuple2<String, Double>, Double, String>() {
            @Override
            public Tuple2<Double, String> call(Tuple2<String, Double> topicHappiness)
                    throws Exception {
                return new Tuple2<Double, String>(topicHappiness._2(),
                        topicHappiness._1());
            }
        });

        JavaPairDStream<Double, String> happiest60 = happinessTopicPairs.transformToPair(
                new Function<JavaPairRDD<Double, String>, JavaPairRDD<Double, String>>() {
                    @Override
                    public JavaPairRDD<Double, String> call(JavaPairRDD<Double,
                            String> happinessAndTopics) throws Exception {
                        return happinessAndTopics.sortByKey();
                    }
                }
        );

        happiest60.foreachRDD(new Function<JavaPairRDD<Double, String>, Void>() {
            @Override
            public Void call(JavaPairRDD<Double, String> happinessTopicPairs) throws Exception {
                List<Tuple2<Double, String>> topList = happinessTopicPairs.take(10);
                System.out.println(
                        String.format("\nHappiest topics in last 10 seconds (%s total):",
                                happinessTopicPairs.count()));
                for (Tuple2<Double, String> pair : topList) {
                    System.out.println(
                            String.format("%s (%s happiness)", pair._2(), pair._1()));
                }
                return null;
            }
        });

        jssc.start();
        jssc.awaitTermination();
    }
}
