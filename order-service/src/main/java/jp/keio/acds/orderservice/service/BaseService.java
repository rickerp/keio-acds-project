package jp.keio.acds.orderservice.service;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.exception.transaction.*;
import jp.keio.acds.orderservice.exception.InternalServerErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class BaseService {

    private static final int MAX_TRANSACTION_RETRIES = 3;

    private static final String JOIN_URI = "/api/join/";
    private static final String PREPARE_URI = "/api/prepare/";
    private static final String COMMIT_URI = "/api/commit/";
    private static final String ROLLBACK_URI = "/api/rollback/";

    private final DistributedTransactionManager manager;

    private final TwoPhaseCommitTransactionManager microserviceManager;

    BaseService(DistributedTransactionManager manager, TwoPhaseCommitTransactionManager microserviceManager) {
        this.manager = manager;
        this.microserviceManager = microserviceManager;
    }


    public <R> R execute(Transaction<R> transaction, DistributedTransaction dtx) throws InterruptedException {
        int retryCount = 0;

        while (true) {
            DistributedTransaction tx = dtx == null ? startTransaction() : dtx;

            try {
                return transaction.execute(tx);
            } catch (CommitConflictException | CrudConflictException e) {
                retryTransaction(++retryCount);
            } catch (CommitException | CrudException | UnknownTransactionStatusException e) {
                throw new InternalServerErrorException("ERROR : ScalarDB error", e);
            } finally {
                abortTransaction(tx);
            }
        }
    }

    public <R> R execute(MicroserviceTransaction<R> transaction, WebClient client) throws InterruptedException {
        int retryCount = 0;

        while (true) {
            TwoPhaseCommitTransaction tx = startMicroserviceTransaction();

            try {
                return transaction.execute(tx);
            } catch (CommitConflictException | CrudConflictException | PreparationConflictException e) {
                retryTransaction(++retryCount);
            } catch (CommitException | CrudException | PreparationException | UnknownTransactionStatusException e) {
                throw new InternalServerErrorException("ERROR : ScalarDB error", e);
            } finally {
                rollbackMicroserviceTransaction(tx, client);
            }
        }
    }

    private DistributedTransaction startTransaction() {
        try {
            return manager.start();
        } catch (TransactionException e) {
            throw new InternalServerErrorException("ERROR : Could not start transaction manager", e);
        }
    }

    private TwoPhaseCommitTransaction startMicroserviceTransaction() {
        try {
            return microserviceManager.start();
        } catch (TransactionException e) {
            throw new InternalServerErrorException("ERROR : Could not start two phase commit transaction manager", e);
        }
    }

    private void retryTransaction(int retryCount) throws InterruptedException {
        if (retryCount == MAX_TRANSACTION_RETRIES) {
            throw new InternalServerErrorException("ERROR : Failed transaction after 3 retries");
        }
        TimeUnit.MILLISECONDS.sleep(100);
    }

    private void abortTransaction(DistributedTransaction tx) {
        try {
            tx.abort();
        } catch (AbortException ex) {
            throw new InternalServerErrorException("ERROR : Could not abort transaction");
        }
    }

    private void rollbackMicroserviceTransaction(TwoPhaseCommitTransaction tx, WebClient client) {
        try {
            tx.rollback();
            sendRollbackRequest(tx.getId(), client);
        } catch (RollbackException ex) {
            throw new InternalServerErrorException("ERROR : Could not rollback transaction");
        }
    }

    protected void sendJoinRequest(String txId, WebClient client) {
        client.get()
                .uri(JOIN_URI + txId)
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofSeconds(5L));
    }

    protected void sendPrepareRequest(String txId, WebClient client) {
        client.get()
                .uri(PREPARE_URI + txId)
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofSeconds(5L));
    }

    protected void sendCommitRequest(String txId, WebClient client) {
        client.get()
                .uri(COMMIT_URI + txId)
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofSeconds(5L));
    }

    protected void sendRollbackRequest(String txId, WebClient client) {
        client.get()
                .uri(ROLLBACK_URI + txId)
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofSeconds(5L));
    }
}
