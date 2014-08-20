package name.evdubs.harmonia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.Date;

import com.xeiam.xchange.Exchange;
import com.xeiam.xchange.ExchangeFactory;
import com.xeiam.xchange.ExchangeSpecification;
import com.xeiam.xchange.bitfinex.v1.BitfinexExchange;
import com.xeiam.xchange.bitfinex.v1.BitfinexOrderType;
import com.xeiam.xchange.bitfinex.v1.dto.account.BitfinexBalancesResponse;
import com.xeiam.xchange.bitfinex.v1.dto.marketdata.BitfinexLendDepth;
import com.xeiam.xchange.bitfinex.v1.dto.marketdata.BitfinexLendLevel;
import com.xeiam.xchange.bitfinex.v1.dto.trade.BitfinexCreditResponse;
import com.xeiam.xchange.bitfinex.v1.dto.trade.BitfinexOfferStatusResponse;
import com.xeiam.xchange.bitfinex.v1.service.polling.BitfinexAccountServiceRaw;
import com.xeiam.xchange.bitfinex.v1.service.polling.BitfinexMarketDataServiceRaw;
import com.xeiam.xchange.bitfinex.v1.service.polling.BitfinexTradeServiceRaw;
import com.xeiam.xchange.dto.Order.OrderType;
import com.xeiam.xchange.dto.trade.FixedRateLoanOrder;
import com.xeiam.xchange.dto.trade.FloatingRateLoanOrder;

/**
 * Main entry point
 *
 */
public class Harmonia {
	public static void main(String[] args) {
		// Use the factory to get BFX exchange API using default settings
		Exchange bfx = ExchangeFactory.INSTANCE.createExchange(BitfinexExchange.class.getName());

		ExchangeSpecification bfxSpec = bfx.getDefaultExchangeSpecification();
		String apiKey = "";
		String secretKey = "";
		
		try {
			System.out.print("API Key: ");
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			apiKey = br.readLine();
			
			System.out.print("Secret Key: ");
			secretKey = br.readLine();
			
			br.close();
		} catch (IOException e2) {
			System.out.println("Could not read in keys; exiting.");
			e2.printStackTrace();
			System.exit(1);
		}
		
		bfxSpec.setApiKey(apiKey);
		bfxSpec.setSecretKey(secretKey);

		bfx.applySpecification(bfxSpec);

		// Get the necessary services
		BitfinexMarketDataServiceRaw marketDataService = (BitfinexMarketDataServiceRaw) bfx
				.getPollingMarketDataService();
		BitfinexAccountServiceRaw accountService = (BitfinexAccountServiceRaw) bfx.getPollingAccountService();
		BitfinexTradeServiceRaw tradeService = (BitfinexTradeServiceRaw) bfx.getPollingTradeService();

		BigDecimal fifty = new BigDecimal("50");
		BigDecimal rateMax = new BigDecimal("2555"); // 7% per day * 365 days
		double millisecondsInDay = 86400000.0;

		BigDecimal depositFunds = BigDecimal.ZERO;
		double estimatedAccumulatedInterest = 0.0;
		Date previousLoopExecutionDate = new Date();

		while (true) {
			try {
				BitfinexBalancesResponse[] balances = accountService.getBitfinexAccountInfo();
				BitfinexOfferStatusResponse[] activeOffers = tradeService.getBitfinexOpenOffers();
				BitfinexCreditResponse[] activeCredits = tradeService.getBitfinexActiveCredits();

				Date currentLoopExecutionDate = new Date();
				BigDecimal activeCreditAmount = BigDecimal.ZERO;
				double activeCreditInterest = 0.0;

				for (BitfinexCreditResponse credit : activeCredits) {
					activeCreditAmount = activeCreditAmount.add(credit.getAmount());

					activeCreditInterest = activeCreditInterest
							+ credit.getAmount().doubleValue()
							* (credit.getRate().doubleValue() / 365 / 100) // rate per day in whole number terms (not percentage)
							* ((double) (currentLoopExecutionDate.getTime() - previousLoopExecutionDate.getTime()) / millisecondsInDay);
				}
				
				previousLoopExecutionDate = currentLoopExecutionDate;

				BigDecimal activeOfferAmount = BigDecimal.ZERO;
				BigDecimal activeOfferRate = BigDecimal.ZERO;
				boolean activeOfferFrr = false;

				for (BitfinexOfferStatusResponse offer : activeOffers) {
					activeOfferAmount = activeOfferAmount.add(offer.getRemainingAmount());
					activeOfferRate = offer.getRate();
					activeOfferFrr = BigDecimal.ZERO.equals(offer.getRate());
				}

				for (BitfinexBalancesResponse balance : balances) {
					if ("deposit".equalsIgnoreCase(balance.getType()) && "USD".equalsIgnoreCase(balance.getCurrency())) {
						if (depositFunds.compareTo(balance.getAmount()) == 0) {
							estimatedAccumulatedInterest = estimatedAccumulatedInterest + activeCreditInterest;
							System.out.println("Estimated total accrued interest " + estimatedAccumulatedInterest);
						} else {
							System.out.println("BFX paid " + (balance.getAmount().subtract(depositFunds))
									+ " (post-fees) with the estimate of " + estimatedAccumulatedInterest + " (pre-fees)");
							depositFunds = balance.getAmount();
							estimatedAccumulatedInterest = 0.0;
						}
					}
				}

				BigDecimal inactiveFunds = depositFunds.subtract(activeCreditAmount);

				// If we have $50 or more of inactive funding, get data and go through calculation
				if (inactiveFunds.compareTo(fifty) >= 0) {
					BigDecimal bestBidRate = BigDecimal.ZERO;
					boolean bidFrr = false;
					BitfinexLendDepth book = marketDataService.getBitfinexLendBook("USD", 5000, 5000);

					for (BitfinexLendLevel bidLevel : book.getBids()) {
						if (bidLevel.getRate().compareTo(bestBidRate) > 0) {
							bestBidRate = bidLevel.getRate();
						}

						if ("Yes".equalsIgnoreCase(bidLevel.getFrr())) {
							bidFrr = true;
						}
					}

					// If the FRR is demanded by buyers and our current order differs, send an order for FRR
					if (bidFrr
							&& !matchesCurrentOrder(activeOfferFrr, activeOfferAmount, activeOfferRate, true,
									inactiveFunds, BigDecimal.ZERO)) {

						// Cancel existing orders and send new FRR order
						cancelPreviousAndSendNewOrder(tradeService, activeOffers, true, inactiveFunds, BigDecimal.ZERO);

					} else { // flash return rate demanded by sellers, send a competitive fixed rate order
						BigDecimal bestAskOutsideBestBid = rateMax;
						BigDecimal secondBestAskOutsideBestBid = rateMax;
						BigDecimal bestAskOutsideBestBidAmount = BigDecimal.ZERO;
						boolean bestAskFrr = false;

						for (BitfinexLendLevel askLevel : book.getAsks()) {
							if (askLevel.getRate().compareTo(bestBidRate) > 0) {
								if (askLevel.getRate().compareTo(bestAskOutsideBestBid) < 0) {
									secondBestAskOutsideBestBid = bestAskOutsideBestBid;
									bestAskOutsideBestBid = askLevel.getRate();
									bestAskOutsideBestBidAmount = BigDecimal.ZERO;
								}

								// Add to the amount if we've found the best ask
								if (askLevel.getRate().compareTo(bestAskOutsideBestBid) == 0) {
									bestAskOutsideBestBidAmount = bestAskOutsideBestBidAmount.add(askLevel.getAmount());
								}

								if ("Yes".equals(askLevel.getFrr())) {
									bestAskFrr = true;
								} else {
									bestAskFrr = false;
								}
							}
						}

						// If the best offer is FRR, just sit with everyone else
						if (bestAskFrr
								&& !matchesCurrentOrder(activeOfferFrr, activeOfferAmount, activeOfferRate, true,
										inactiveFunds, BigDecimal.ZERO)) {
							// Cancel existing orders and send new FRR order
							cancelPreviousAndSendNewOrder(tradeService, activeOffers, true, inactiveFunds,
									BigDecimal.ZERO);

						} else if (!bestAskFrr) {
							// Best ask is not FRR, we need to send a competitive fixed rate
							System.out.println("Comparing best ask outside best bid amount "
									+ bestAskOutsideBestBidAmount + " with our offer amount " + activeOfferAmount);
							if (bestAskOutsideBestBidAmount.compareTo(activeOfferAmount) == 0) {
								// Don't stay out there alone
								// Join second best ask outside of best bid
								cancelPreviousAndSendNewOrder(tradeService, activeOffers, false, inactiveFunds,
										secondBestAskOutsideBestBid);
							} else if (!matchesCurrentOrder(activeOfferFrr, activeOfferAmount, activeOfferRate, false,
									inactiveFunds, bestAskOutsideBestBid)) {
								// Join best ask outside of best bid
								cancelPreviousAndSendNewOrder(tradeService, activeOffers, false, inactiveFunds,
										bestAskOutsideBestBid);
							} else {
								System.out.println("Matched previous isFrr: " + activeOfferFrr + " amount: "
										+ activeOfferAmount + " rate: " + activeOfferRate);
							}
						} else {
							System.out.println("Matched previous isFrr: " + activeOfferFrr + " amount: "
									+ activeOfferAmount + " rate: " + activeOfferRate);
						}
					}

				} else {
					System.out.println("Difference " + depositFunds.subtract(activeCreditAmount)
							+ " not enough to post order");
				}

			} catch (IOException e1) {
				e1.printStackTrace();
			}

			try {
				Thread.sleep(20000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private static boolean matchesCurrentOrder(boolean currentFrr, BigDecimal currentAmount, BigDecimal currentRate,
			boolean newFrr, BigDecimal newAmount, BigDecimal newRate) {

		System.out.println("Comparing currentFrr: " + currentFrr + " with newFrr: " + newFrr);
		if (currentFrr != newFrr)
			return false;

		System.out.println("Comparing currentAmount: " + currentAmount + " with newAmount: " + newAmount);
		if (currentAmount.compareTo(newAmount) != 0)
			return false;

		System.out.println("Comparing currentRate: " + currentRate + " with newRate: " + newRate);
		if (currentRate.compareTo(newRate) != 0)
			return false;

		return true;
	}

	private static void cancelPreviousAndSendNewOrder(BitfinexTradeServiceRaw tradeService,
			BitfinexOfferStatusResponse[] activeOffers, boolean isFrr, BigDecimal amount, BigDecimal rate)
			throws IOException {
		// Cancel existing orders
		if (activeOffers.length != 0) {
			for (BitfinexOfferStatusResponse offer : activeOffers) {
				System.out.println("Cancelling " + offer.toString());
				tradeService.cancelBitfinexOffer(Integer.toString(offer.getId()));
			}
		} else {
			System.out.println("Found no previous order to cancel");
		}

		if (isFrr) {
			FloatingRateLoanOrder order = new FloatingRateLoanOrder(OrderType.ASK, "USD", amount, 30, "", null,
					BigDecimal.ZERO);
			System.out.println("Sending " + order.toString());
			tradeService.placeBitfinexFloatingRateLoanOrder(order, BitfinexOrderType.MARKET);
		} else {
			FixedRateLoanOrder order = new FixedRateLoanOrder(OrderType.ASK, "USD", amount, 2, "", null, rate);
			System.out.println("Sending " + order.toString());
			tradeService.placeBitfinexFixedRateLoanOrder(order, BitfinexOrderType.LIMIT);
		}
	}
}