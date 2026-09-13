package com.example.demo.dto;

public class TransferResponse {
    private int id;
    private String status;
    private String idempotencyKey;
    private boolean replay;

    public TransferResponse() { }

    public TransferResponse(int id, String status, String idempotencyKey) {
        this(id, status, idempotencyKey, false);
    }

    public TransferResponse(int id, String status, String idempotencyKey, boolean replay) {
        this.id = id;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.replay = replay;
    }

    public int getId() { return id; }
    public String getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean isReplay() { return replay; }
}
