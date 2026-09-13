package com.paytm.wallettransfer.repository;

import com.paytm.wallettransfer.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Integer> {
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
