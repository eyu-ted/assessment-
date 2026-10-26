package com.safari.practice.assemnet;


import java.math.BigDecimal;
import java.util.concurrent.locks.ReentrantLock;

public class Account {




    private BigDecimal balance;
    private String id;

    private ReentrantLock lock = new ReentrantLock();

    public Account(BigDecimal balance, String id){

        if (id == null || id.isBlank()){
           throw new IllegalArgumentException("id cannot be empty or null");
        }

        this.balance = balance;
        this.id = id;

    }

    public String getId(){
        return this.id;
    }
    public BigDecimal getBigDecimal(){
        return this.balance;
    }

    public void deposit(BigDecimal amount){

        validate(amount);

        lock.lock();
        try{
            this.balance.add(amount);

        }finally{
            lock.unlock();
        }


    }

    public void withdrawl(BigDecimal amount){

        validate(amount);

        lock.lock();
        try{
            if(amount.compareTo(this.balance)<0){
                throw new IllegalArgumentException("Insuficcent balance");
            }
            this.balance.subtract(amount);

        }finally{
            lock.unlock();
        }


    }


    public void getlock(){
        this.lock.lock();
    }

    public void unlock(){
        this.lock.unlock();
    }

    public void validate(BigDecimal amount){
        if(amount.compareTo(BigDecimal.ZERO)<0){
            throw new IllegalArgumentException("ammount cannot be lessthan Zero");
        }
    }





}
