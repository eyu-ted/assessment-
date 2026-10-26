package com.safari.practice.assemnet;

import java.util.concurrent.ConcurrentHashMap;
import  java.util.*;


public class repo {

    Map<String, Account> accounts = new ConcurrentHashMap<>();


    public void save(String id, Account acc){

        if(id == null || id.isBlank()){
            throw new IllegalArgumentException("id cannot be empty or null");
        }
        if (acc == null){
            throw new IllegalArgumentException("account cannot be null");
        }

        Account exist = this.accounts.putIfAbsent(id,acc);

        if (exist!=null){
            throw new IllegalArgumentException("account with this id already exists");
        }



    }

    public Account findbyId(String id){

        if(id == null || id.isBlank()){
            throw new IllegalArgumentException("id cannot be empty or null");
        }

        Account acc = this.accounts.get(id);

        if (acc == null){
            throw new IllegalArgumentException("id not found");
        }

        return acc;

    }
}
