package com.paytm.wallettransfer.dto;

public class WalletResponse {
    private int id;
    private int userId;
    private int balance;
    private boolean created;
    private String note;

    public WalletResponse() { }

    public WalletResponse(int id, int userId, int balance, boolean created) {
        this(id, userId, balance, created, null);
    }

    public WalletResponse(int id, int userId, int balance, boolean created, String note) {
        this.id = id;
        this.userId = userId;
        this.balance = balance;
        this.created = created;
        this.note = note;
    }

    public int getId() { return id; }
    public int getUserId() { return userId; }
    public int getBalance() { return balance; }
    public boolean isCreated() { return created; }
    public String getNote() { return note; }
}
