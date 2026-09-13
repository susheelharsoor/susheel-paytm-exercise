package com.paytm.wallettransfer.entity;

import com.paytm.wallettransfer.enums.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class Transfer {

    protected Transfer() { }

    public Transfer(Wallet fromWallet, Wallet toWallet, int amountPaise, String idempotencyKey) {
        this(fromWallet, toWallet, amountPaise, idempotencyKey, TransferStatus.COMPLETED);
    }

    public Transfer(Wallet fromWallet, Wallet toWallet, int amountPaise, String idempotencyKey, TransferStatus status) {
        this.fromWallet = fromWallet;
        this.toWallet = toWallet;
        this.amountPaise = amountPaise;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne
    @JoinColumn(name = "from_wallet_id")
    private Wallet fromWallet;

    @ManyToOne
    @JoinColumn(name = "to_wallet_id")
    private Wallet toWallet;

    private int amountPaise;

    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    @Column(unique = true, nullable = false)
    private String idempotencyKey;

    public int getId() { return id; }

    public Wallet getFromWallet() { return fromWallet; }

    public Wallet getToWallet() { return toWallet; }

    public int getAmountPaise() { return amountPaise; }

    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
}
