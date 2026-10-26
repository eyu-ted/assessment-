package com.safari.practice.walletTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.safari.practice.assemnet.Account;
import com.safari.practice.assemnet.Service;
import com.safari.practice.assemnet.repo;

public class walletTest {

    @Test
    public void concurrenncy_and_deadlock_free_test() throws InterruptedException{
        repo rep = new repo();
        Service ser = new Service(rep);


        String id1 = ser.create_account(new BigDecimal(10000));
        String id2 = ser.create_account(new BigDecimal(10000));

        assertEquals(id1, "0");
        assertEquals(id2, "1");

        int thread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0 ; i<thread ; i++){
            executor.submit(()->{
                String key = "" + ThreadLocalRandom.current().nextInt(2147483647);

                ser.transfer(id2, id1, new BigDecimal(500), key);

                key = "" + ThreadLocalRandom.current().nextInt(2147483647);

                ser.transfer(id1, id2, new BigDecimal(500), key);
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));


        assertEquals(new BigDecimal(20000), ser.findaccountbyid(id2).getbalance().add(ser.findaccountbyid(id1).getbalance()));






    }

}
