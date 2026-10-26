package com.safari.practice.assemnet;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

public class Service {

    repo repo;

    AtomicInteger i = new AtomicInteger(0);

    public Service(repo rep){

        this.repo= rep;
    }

    public String create(BigDecimal inital_funding){

        if (inital_funding.compareTo(BigDecimal.ZERO)<0){
            throw new IllegalArgumentException("inial funding cannot lessthan Zero");
        }

        String id = ""+ i.getAndIncrement();

        Account acc = new Account( inital_funding,id);
        this.repo.save(id, acc);

        return id;

    }


    public void transfer(String from, String to, BigDecimal amount){

        Account first;
        Account second;

        if (from.compareTo(to) == 0){
            throw new IllegalArgumentException("self transfer is not allowed");
        }
        validate(amount);

        if (from.compareTo(to)<0){
            first = findaccountbyid(from);
            second = findaccountbyid(to);

        }else{
            first = findaccountbyid(to);
            second = findaccountbyid(from);
        }

        first.getlock();
        try{
            second.getlock();
            try{

                if (first.getbalance().compareTo(amount)<0){
                    throw new IllegalArgumentException("Insufficent account");
                }

                first.withdrawl(amount);
                second.deposit(amount);

            }finally{
                second.unlock();
            }
        }finally{
            first.unlock();
        }






    }

    public Account findaccountbyid(String id){
        if (id == null || id.isBlank()){
           throw new IllegalArgumentException("id cannot be empty or null");
        }

        return this.repo.findbyId(id);
    }

    public void validate(BigDecimal amount){
        if(amount.compareTo(BigDecimal.ZERO)<0){
            throw new IllegalArgumentException("ammount cannot be lessthan Zero");
        }
    }










}
