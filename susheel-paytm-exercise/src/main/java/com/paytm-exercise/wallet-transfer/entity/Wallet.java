package com.example.demo.entity;

import com.example.demo.exception.InsufficientBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
@org.hibernate.annotations.Check(constraints = "balance >= 0")
public class Wallet {

    protected Wallet() { }

    public Wallet(int userId, int initialBalance) {
        this.userId = userId;
        this.balance = initialBalance;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(unique = true, nullable = false)
    private int userId;

    private int balance;

    public int getId() { return id; }

    public int getUserId() { return userId; }

    public int getBalance() { return balance; }

    public void debit(int amountPaise) {
        if (amountPaise > balance) {
            throw new InsufficientBalanceException(id);
        }
        this.balance -= amountPaise;
    }

    public void credit(int amountPaise) {
        this.balance += amountPaise;
    }
}
