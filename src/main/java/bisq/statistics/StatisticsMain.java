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

import bisq.core.app.BisqEnvironment;
import bisq.core.app.BisqExecutable;
import bisq.core.app.HeadlessExecutable;

import bisq.common.UserThread;
import bisq.common.app.AppModule;

import joptsimple.OptionSet;

import com.google.inject.Injector;

public class StatisticsMain extends HeadlessExecutable {

    private BisqEnvironment bisqEnvironment;
    private Statistics statistics;

    public static void main(String[] args) throws Exception {
        // We don't want to do the full argument parsing here as that might easily change in update versions
        // So we only handle the absolute minimum which is APP_NAME, APP_DATA_DIR_KEY and USER_DATA_DIR
        BisqEnvironment.setDefaultAppName("bisq_statistics");
        if (BisqExecutable.setupInitialOptionParser(args)) {
            // For some reason the JavaFX launch process results in us losing the thread context class loader: reset it.
            // In order to work around a bug in JavaFX 8u25 and below, you must include the following code as the first line of your realMain method:
            Thread.currentThread().setContextClassLoader(StatisticsMain.class.getClassLoader());

            new StatisticsMain().execute(args);
        }
    }

    @Override
    protected void doExecute(OptionSet options) {
        super.doExecute(options);

        checkMemory(bisqEnvironment, statistics);

        keepRunning();
    }

    @Override
    protected void setupEnvironment(OptionSet options) {
        bisqEnvironment = getBisqEnvironment(options);
        Statistics.setEnvironment(bisqEnvironment);
    }

    @Override
    protected void launchApplication() {
        UserThread.execute(() -> {
            try {
                statistics = new Statistics();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    protected AppModule getModule() {
        //TODO not impl yet
        return null;
    }

    @Override
    protected Injector getInjector() {
        //TODO not impl yet
        return null;
    }
}
