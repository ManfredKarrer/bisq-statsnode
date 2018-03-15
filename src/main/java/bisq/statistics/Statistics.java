/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.statistics;

import bisq.core.app.AppOptionKeys;
import bisq.core.app.AppSetup;
import bisq.core.app.AppSetupWithP2P;
import bisq.core.app.BisqEnvironment;
import bisq.core.arbitration.ArbitratorManager;
import bisq.core.btc.BaseCurrencyNetwork;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.WalletsSetup;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.offer.OfferBookService;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.statistics.TradeStatisticsManager;

import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.P2PService;

import bisq.common.CommonOptionKeys;
import bisq.common.UserThread;
import bisq.common.app.Capabilities;
import bisq.common.app.Log;
import bisq.common.app.Version;
import bisq.common.crypto.LimitedKeyStrengthException;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.Utilities;

import org.bitcoinj.store.BlockStoreException;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.lang3.exception.ExceptionUtils;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.NoSuchAlgorithmException;
import java.security.Security;

import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Arrays;

import ch.qos.logback.classic.Level;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Statistics {
    public static final String VERSION = "0.6.1";

    private static BisqEnvironment bisqEnvironment;

    public static void setEnvironment(BisqEnvironment bisqEnvironment) {
        Statistics.bisqEnvironment = bisqEnvironment;
    }

    private final Injector injector;
    private final StatisticsModule statisticsModule;
    private final OfferBookService offerBookService;
    private final PriceFeedService priceFeedService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final P2PService p2pService;
    private final AppSetup appSetup;

    public Statistics() {
        String logPath = Paths.get(bisqEnvironment.getProperty(AppOptionKeys.APP_DATA_DIR_KEY), "bisq").toString();
        Log.setup(logPath);
        Log.setLevel(Level.toLevel(bisqEnvironment.getRequiredProperty(CommonOptionKeys.LOG_LEVEL_KEY)));
        log.info("Log files under: " + logPath);
        log.info("Statistics.VERSION: " + VERSION);
        log.info("Bisq exchange Version{" +
                "VERSION=" + Version.VERSION +
                ", P2P_NETWORK_VERSION=" + Version.P2P_NETWORK_VERSION +
                ", LOCAL_DB_VERSION=" + Version.LOCAL_DB_VERSION +
                ", TRADE_PROTOCOL_VERSION=" + Version.TRADE_PROTOCOL_VERSION +
                ", BASE_CURRENCY_NETWORK=NOT SET" +
                ", getP2PNetworkId()=NOT SET" +
                '}');
        Utilities.printSysInfo();

        // setup UncaughtExceptionHandler
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            // Might come from another thread
            if (throwable.getCause() != null && throwable.getCause().getCause() != null &&
                    throwable.getCause().getCause() instanceof BlockStoreException) {
                log.error(throwable.getMessage());
            } else {
                log.error("Uncaught Exception from thread " + Thread.currentThread().getName());
                log.error("throwableMessage= " + throwable.getMessage());
                log.error("throwableClass= " + throwable.getClass());
                log.error("Stack trace:\n" + ExceptionUtils.getStackTrace(throwable));
                throwable.printStackTrace();
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);

        try {
            Utilities.checkCryptoPolicySetup();
        } catch (NoSuchAlgorithmException | LimitedKeyStrengthException e) {
            e.printStackTrace();
            UserThread.execute(this::shutDown);
        }
        Security.addProvider(new BouncyCastleProvider());

        final BaseCurrencyNetwork baseCurrencyNetwork = BisqEnvironment.getBaseCurrencyNetwork();
        final String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);

        statisticsModule = new StatisticsModule(bisqEnvironment);
        injector = Guice.createInjector(statisticsModule);

        p2pService = injector.getInstance(P2PService.class);
        offerBookService = injector.getInstance(OfferBookService.class);
        priceFeedService = injector.getInstance(PriceFeedService.class);
        tradeStatisticsManager = injector.getInstance(TradeStatisticsManager.class);

        // We need the price feed for market based offers
        priceFeedService.setCurrencyCode("USD");
        p2pService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onUpdatedDataReceived() {
                // we need to have tor ready
                log.info("onBootstrapComplete: we start requestPriceFeed");
                priceFeedService.requestPriceFeed(price -> log.info("requestPriceFeed. price=" + price),
                        (errorMessage, throwable) -> log.warn("Exception at requestPriceFeed: " + throwable.getMessage()));

                tradeStatisticsManager.onAllServicesInitialized();
            }
        });

        appSetup = injector.getInstance(AppSetupWithP2P.class);
        Capabilities.setSupportedCapabilities(new ArrayList<>(Arrays.asList(
                Capabilities.Capability.TRADE_STATISTICS.ordinal(),
                Capabilities.Capability.TRADE_STATISTICS_2.ordinal(),
                Capabilities.Capability.ACCOUNT_AGE_WITNESS.ordinal(),
                Capabilities.Capability.COMP_REQUEST.ordinal()
        )));
        appSetup.start();
    }

    private void shutDown() {
        gracefulShutDown(() -> {
            log.debug("Shutdown complete");
            System.exit(0);
        });
    }

    public void gracefulShutDown(ResultHandler resultHandler) {
        log.debug("gracefulShutDown");
        try {
            if (injector != null) {
                injector.getInstance(ArbitratorManager.class).shutDown();
                injector.getInstance(OpenOfferManager.class).shutDown(() -> injector.getInstance(P2PService.class).shutDown(() -> {
                    injector.getInstance(WalletsSetup.class).shutDownComplete.addListener((ov, o, n) -> {
                        statisticsModule.close(injector);
                        log.debug("Graceful shutdown completed");
                        resultHandler.handleResult();
                    });
                    injector.getInstance(WalletsSetup.class).shutDown();
                    injector.getInstance(BtcWalletService.class).shutDown();
                    injector.getInstance(BsqWalletService.class).shutDown();
                }));
                // we wait max 5 sec.
                UserThread.runAfter(resultHandler::handleResult, 5);
            } else {
                UserThread.runAfter(resultHandler::handleResult, 1);
            }
        } catch (Throwable t) {
            log.debug("App shutdown failed with exception");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
