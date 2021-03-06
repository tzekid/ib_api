package ib.lib

import bon.*
import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.Types
import ib.IB
import ib.IbConfig
import ib.support.*

private val log = Log("IB")


// All methods are synchronized and will be called sequentially.
// This is done to provide request isolation, some methods like `client.reqMarketDataType()` are global
// and calling it in one request may affect another request.
class IBImpl(port: Int = IbConfig.ib_port) : IB() {
  private val queue   = IBQueue()
  private val workers = mutable_list_of<Worker>()

  init {
    log.info(
      "will be connected to TWS on port $port with ${IbConfig.workers_count} parallel connections"
    )
    for (worker_id in 1..IbConfig.workers_count) {
      workers.add(Worker(queue, port, worker_id))
    }
  }


  override fun get_portfolio(): List<Portfolio> {
    get_account_cash();
    log.info("get_portfolio")
    val events = queue.process(
      "get_portfolio",
      null,
      { _, request_id, client -> client.reqPositionsMulti(request_id, "all", "") },
      { _, request_id, client ->
        client.cancelPositionsMulti(request_id)
      },
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.timeout_ms
    )

    // Grouping events by account
    val accounts = events
      .map { e -> e as IBWrapper.ContractWithPositionEvent }
      .groupBy { e -> e.account_id }

    // Getting cash
    val cash = get_account_cash()

    // Parsing
    assert(accounts.keys == cash.keys) {
      "accounts for portfolio positions ${accounts.keys} and cash positions ${cash.keys} are not matching"
    }

    return accounts.map { events, account_id ->
      val stocks  = mutable_list_of<PortfolioPosition<PortfolioStockContract>>()
      val options = mutable_list_of<PortfolioPosition<PortfolioOptionContract>>()
      events.each { event ->
        val position = Converter.parse_and_add_portfolio_position(account_id, event)
        when (position.contract) {
          is PortfolioStockContract  -> stocks.add(position as PortfolioPosition<PortfolioStockContract>)
          is PortfolioOptionContract -> options.add(position as PortfolioPosition<PortfolioOptionContract>)
        }
      }

      Portfolio(
        account_id    = account_id,
        stocks        = stocks,
        stock_options = options,
        cash_in_usd   = cash[account_id]!!
      )
    }.values.to_list()
  }


  // Get accounts cash, all cash converted to USD
  private fun get_account_cash(): Dict<String, Double> {
    log.info("get_account_summary")
    val events = queue.process(
      "get_portfolio",
      null,
      { _, request_id, client ->
        client.reqAccountSummary(request_id, "All", "TotalCashValue")
      },
      { _, request_id, client ->
        client.cancelAccountSummary(request_id)
      },
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.timeout_ms
    )

    val cash = mutable_dict_of<String, Double>()
    events.each { raw_event ->
      val event = raw_event as IBWrapper.AccountSummaryEvent
      assert(event.account_id != "") { "empty account" }
      assert(event.tag == "TotalCashValue") { "unexpected tag '${event.tag}'" }
      assert(event.currency == "USD") { "unexpected currency '${event.currency}'" }
      assert(!(event.account_id in cash)) { "multiple events for same account_id" }
      cash[event.account_id] = event.value.toDouble()
    }

    return dict_of(cash)
  }


  override fun get_stock_contract(symbol: String, exchange: String, currency: String): StockContract {
    log.info("get_stock_contract $symbol $exchange:$currency")
    val cd = queue.process(
      "get_contract",
      null,
      { _, request_id, client ->
        val contract = Contract()
        contract.symbol(symbol)
        contract.currency(currency)
        contract.exchange(exchange)
        contract.secType(Types.SecType.STK)
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> {
          assert(events.size == 1) { "Wrong events size ${events.size}" }
          events.first() as ContractDetails
        }
        else               -> null
      }},
      IbConfig.timeout_ms
    )
    return Converter.parse_stock_contract(cd)
  }


  override fun get_stock_option_chain_contracts_by_expiration(
    symbol: String, expiration: String, option_exchange: String, currency: String
  ): List<OptionContract> {
    log.info("get_stock_option_chain_contracts_by_expiration $symbol $expiration $option_exchange:$currency")
    val events = queue.process(
      "get_stock_option_chain_contracts",
      null,
      { _, request_id, client ->
        val contract = Contract()
        contract.symbol(symbol)
        contract.lastTradeDateOrContractMonth(Converter.yyyy_mm_dd_to_yyyymmdd(expiration))
        contract.currency(currency)
        contract.exchange(option_exchange)
        contract.secType(Types.SecType.OPT)
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.large_timeout_ms
    )

    return events.map { event -> when (event) {
      is ContractDetails -> Converter.parse_option_contract(
        symbol, option_exchange, currency, event
      ).first
      else -> throw Exception("wrong option contract class ${event::class}")
    }}
  }


  override fun get_stock_contracts(symbol: String): List<StockContract> {
    log.info("get_stock_contracts $symbol")
    val events = queue.process(
      "get_contracts",
      null,
      { _, request_id, client ->
        val contract = Contract()
        contract.symbol(symbol)
        contract.secType(Types.SecType.STK)
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events.map { event -> event as ContractDetails }
        else               -> null
      }},
      IbConfig.timeout_ms
    )
    return events.map(Converter::parse_stock_contract)
  }


  override fun inspect_stock(
    symbol:   String  // MSFT
  ): StockInspection = inspect_stock(this, symbol)


  override fun get_stock_price(
    symbol: String, exchange: String, currency: String, data_type: MarketDataType?
  ): StockPrice {
    log.info("get_stock_price $symbol $exchange:$currency $data_type")
    val contract = Contract()
    contract.symbol(symbol)
    contract.secType(Types.SecType.STK)
    contract.currency(currency)
    contract.exchange(exchange)
    return get_stock_price(contract, data_type)
  }


  override fun get_stock_price_by_id(
    id: Int, exchange: String, currency: String, data_type: MarketDataType?
  ): StockPrice {
    log.info("get_stock_price_by_id $id $exchange:$currency $data_type")

    val contract = Contract()
    contract.conid(id)
    contract.secType(Types.SecType.STK)
    contract.currency(currency)
    contract.exchange(exchange)
    return get_stock_price(contract, data_type)
  }


  private fun get_stock_price(contract: Contract, data_type: MarketDataType?): StockPrice {
    val lp = get_last_price("get_last_stock_price", contract, data_type)
    return StockPrice(
      price      = lp.price,
      data_type  = lp.data_type,
      price_type = lp.price_type
    )
  }


  override fun get_stock_option_chains(
    symbol: String, exchange: String, currency: String
  ): OptionChains {
    log.info("get_stock_option_chain $symbol $exchange:$currency")

    // Getting underlying stock contract
    val ucontract: StockContract = get_stock_contract(symbol, exchange, currency)

    return get_stock_option_chains(symbol, ucontract.id, currency)
  }

  fun get_stock_option_chains(
    symbol: String, id: Int, currency: String
  ): OptionChains {
    // Getting expirations and strikes
    val events = queue.process(
      "get_stock_option_chains",
      "",
      { _, request_id, client ->
        // If exchange provide explicitly it's not returned, seems like it's either bug
        // or the parameter means something else.
        client.reqSecDefOptParams(request_id, symbol, "", "STK", id)
      },
      null,
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.timeout_ms
    )

    // Parsing events
    var chains = events.map { event -> when (event) {
      is IBWrapper.SecurityDefinitionOptionalParameterEvent -> {
        val multiplier = Converter.parse_multiplier(event.multiplier, symbol)
//        assert(multiplier in set_of(100, 1000)) {
//          "wrong option contract multiplier for $symbol expected 100 got ${event.multiplier}"
//        }
        OptionChain(
          option_exchange = event.exchange,
          expirations_asc = event.expirations.map(Converter::yyyymmdd_to_yyyy_mm_dd).sorted(),
          strikes_asc     = event.strikes.to_list().sorted(),
          multiplier      = multiplier
        )
      }
      else -> throw Exception("wrong event class ${event::class}")
    }}

    // Selecting largest chain for every exchange
    // Sorting by size of expirations and strikes and then taking first unique
    chains = chains.sort_by({ it.expirations_asc.size }, { it.strikes_asc.size }).reversed()
    val largest_desc = mutable_list_of<OptionChain>()
    for (chain in chains) {
      if (!largest_desc.any { c -> c.option_exchange == chain.option_exchange}) {
        largest_desc.add(chain)
      }
    }

    return OptionChains(
      largest_desc = largest_desc,
      all          = chains
    )
  }

  override fun get_stock_option_chain_contracts(
    symbol: String, option_exchange: String, currency: String
  ): OptionContracts {
    log.info("get_stock_option_chain_contracts $symbol $option_exchange:$currency")

    // Getting events
    val events = queue.process(
      "get_option_contracts",
      null,
      { _, id, client ->
        val contract = Contract()
        contract.symbol(symbol)
        contract.secType(Types.SecType.OPT)
        contract.currency(currency)
        contract.exchange(option_exchange)
        client.reqContractDetails(id, contract)
      },
      null,
      { _, errors, events, final_event -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.large_timeout_ms
    )

    // Parsing and sorting events
    val contracts = events
      .map { event -> when (event) {
        is ContractDetails -> Converter.parse_option_contract(symbol, option_exchange, currency, event)
        else               -> throw Exception("Wrong event type $event")
      }}
    val multiplier = contracts.first().second
    assert (contracts.all { (_, m) -> m == multiplier }) { "all option contracts should have same multiplier" }

    return OptionContracts(
      multiplier                               = multiplier,
      contracts_asc_by_right_expiration_strike = contracts
        .map { (c, _) -> c }
        .sort_by({ it.right }, { it.expiration }, { it.strike })
    )
  }


  override fun get_stock_option_price(
    symbol:          String,         // MSFT
    right:           String,         // "put" or "call"'
    expiration:      String,         // 2020-08-21
    strike:          Double,         // 120
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): OptionContractPrice {
    log.info("get_stock_option_price $symbol-$right-$expiration-$strike $option_exchange:$currency $data_type")

    val contract = Contract()
    contract.secType(Types.SecType.OPT)
    contract.symbol(symbol)
    contract.right(Converter.right_to_ib_right(right))
    contract.lastTradeDateOrContractMonth(Converter.yyyy_mm_dd_to_yyyymmdd(expiration))
    contract.strike(strike)
    contract.exchange(option_exchange)
    contract.currency(currency)
//    contract.multiplier("100")
    
    val price = get_last_price("get_stock_option_price", contract, data_type)
    return OptionContractPrice(
      price      = price.price,
      data_type  = price.data_type,
      price_type = price.price_type
    )
  }


  override fun get_stock_option_price_by_id(
    id:              Int,            // Contract id 426933553
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): OptionContractPrice {
    log.info("get_stock_option_price_by_id $id $option_exchange:$currency $data_type")

    // // Querying option contract ids if it's not provided, it's very slow operation.
    // if (option_contracts_ids_optional == null) {
    //   option_contracts_ids_optional = map(
    //     get_stock_option_contracts(symbol, exchange, currency).contracts_asc_by_right_expiration_strike,
    //     (contract) -> contract.id
    //   );
    // }

    val contract = Contract()
    contract.exchange(option_exchange)
    contract.currency(currency)
    contract.conid(id)
    contract.secType(Types.SecType.OPT)
    val price = get_last_price("get_stock_option_price_by_id", contract, data_type)
    return OptionContractPrice(
      price      = price.price,
      data_type  = price.data_type,
      price_type = price.price_type
    )
  }


  private class LastPrice(
    val price:      Double,
    val data_type:  MarketDataType,
    val price_type: PriceType
  )

  private fun get_last_price(
    type: String, contract: Contract, data_type: MarketDataType?
  ): LastPrice {
    val prices = get_last_prices(type, list_of(contract), data_type)
    assert(prices.size == 1) { "wrong size for prices ${prices.size}" }
    return prices[0]
  }

  val price_event_types = set_of(
    EventTypes.InternalPriceType.LAST,
    EventTypes.InternalPriceType.CLOSE,

    EventTypes.InternalPriceType.DELAYED_LAST,
    EventTypes.InternalPriceType.DELAYED_CLOSE
  )

  private fun get_last_prices(
    type: String, contracts: List<Contract>, data_type: MarketDataType?
  ): List<LastPrice> {
    return queue.process_all(
      type,
      contracts,
      { contract, request_id, client ->
        if (data_type != null) client.reqMarketDataType(data_type.code)
        client.reqMktData(request_id, contract, "", false, false, null)
      },
      { _, request_id, client ->
        client.cancelMktData(request_id)
      },
      { _, errors, events, _ ->
        if (!errors.is_empty()) throw errors.first() // Throwing just the first error for simplicity

        // Scanning if events contain price and market data type events
        var price_event:      IBWrapper.PriceEvent? = null
        var market_data_type: IBWrapper.MarketDataTypeEvent? = null
        // Reversing to prefer the latest events
        events.reversed().each { event -> when (event) {
          is IBWrapper.PriceEvent -> {
            if (
              (event.type in price_event_types) and
              // If delayed data requested, the Interactive Brokers respond with delayed price = 0
              // Try /api/v1/stock_price?symbol=6752&currency=JPY&data_type=delayed
              (event.price > 0)
            ) price_event = event
          }
          is IBWrapper.MarketDataTypeEvent -> {
            // There could be multiple market data type events, using the event that occured
            // right before the found price and ignoring other events.
            if (price_event != null && market_data_type == null) market_data_type = event

            // if (market_data_type != null) assert(market_data_type!!.type == event.type) {
            //   "Two different MarketDataTypes ${market_data_type!!.type} and ${event.type}"
            // }
            // market_data_type = event
          }
//          is IBWrapper.PriceTimestampEvent -> {
//            if (event.type == TimeType.LAST_TIME || timeEvent.type == TimeType.LAST_TIME_DELAYED) {
//              timeMs = event.time;
//            }
//          }
        }}

        val pevent = price_event; val mevent = market_data_type
        if (pevent != null && mevent != null) {
          LastPrice(
            price      = pevent.price,
            data_type  = mevent.type,
            price_type = pevent.type.to_price_type()
          )
        } else null
      },
      IbConfig.timeout_ms
    )
  }

  //  override fun get_stock_option_prices(
//    symbol:                     String,
//    option_contracts_ids: List<Int>,
//    exchange:                 String?,
//    currency:                 String?
//  ): OptionContractPrices {
//    val exc = exchange ?: default_exchange
//    val cur = currency ?: default_currency
//
//    // // Querying option contract ids if it's not provided, it's very slow operation.
//    // if (option_contracts_ids_optional == null) {
//    //   option_contracts_ids_optional = map(
//    //     get_stock_option_contracts(symbol, exchange, currency).contracts_asc_by_right_expiration_strike,
//    //     (contract) -> contract.id
//    //   );
//    // }
//
//    val inputs = option_contracts_ids.map { id ->
//      val c = Contract()
//      c.conid(id)
//      c.symbol(symbol)
//      c.secType(Types.SecType.OPT)
//      c.currency(cur)
//      c.exchange(exc)
//      c
//    }
//    val prices = get_last_prices("get_stock_option_prices", inputs)
//    return OptionContractPrices(
//      symbol   = symbol,
//      exchange = exc,
//      currency = cur,
//      prices   = prices.map { price, i ->
//        OptionContractPrice(
//          id      = option_contracts_ids[i],
//          price         = price.price,
//          data_type      = price.ib_market_data_type,
//          price_type = price.price_type
//        )
//      }
//    )
//  }
}