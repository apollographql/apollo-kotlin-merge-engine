package com.example;

import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.api.ApolloResponse;
import com.apollographql.apollo.api.Query;
import com.apollographql.apollo.network.http.DefaultHttpEngine;
import com.apollographql.apollo.network.http.HttpNetworkTransport;
import com.apollographql.merge.engine.MergeEngine;
import io.reactivex.rxjava3.core.Flowable;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.rx3.RxConvertKt;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


public class JavaTest {
    @Test
    public void test() {
        ApolloClient apolloClient = new ApolloClient.Builder()
                .networkTransport(
                        new HttpNetworkTransport.Builder()
                                .serverUrl("https://confetti-app.dev/graphql")
                                .httpEngine(new MergeEngine(DefaultHttpEngine.DefaultHttpEngine(30000), 50))
                                .build()
                )
                .build();


        List<ApolloResponse<? extends Query.Data>> responses = Flowable.zip(
                RxConvertKt.asFlowable(apolloClient.query(new GetConferences1Query()).toFlow(), EmptyCoroutineContext.INSTANCE),
                RxConvertKt.asFlowable(apolloClient.query(new GetConferences2Query()).toFlow(), EmptyCoroutineContext.INSTANCE),
                Arrays::asList
        ).blockingFirst();

        System.out.println(responses.stream().map(item -> item.data).collect(Collectors.toList()));
    }
}
