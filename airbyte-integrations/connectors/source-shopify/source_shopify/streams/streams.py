#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#


from typing import Any, Iterable, Mapping, MutableMapping, Optional

import requests
from airbyte_cdk.sources.streams.core import package_name_from_class
from airbyte_cdk.sources.utils.schema_helpers import ResourceSchemaLoader
from requests.exceptions import RequestException
from source_shopify.shopify_graphql.bulk.query import (
    BULK_PARENT_KEY,
    Collection,
    DiscountCode,
    FulfillmentOrder,
    InventoryItem,
    InventoryLevel,
    MetafieldCollection,
    MetafieldCustomer,
    MetafieldDraftOrder,
    MetafieldLocation,
    MetafieldOrder,
    MetafieldProduct,
    MetafieldProductImage,
    MetafieldProductVariant,
    Transaction,
)
from source_shopify.shopify_graphql.graphql import get_query_products
from source_shopify.utils import ApiTypeEnum
from source_shopify.utils import ShopifyRateLimiter as limiter

from .base_streams import (
    IncrementalShopifyGraphQlBulkStream,
    IncrementalShopifyNestedSubstream,
    IncrementalShopifyStream,
    IncrementalShopifyStreamWithDeletedEvents,
    IncrementalShopifySubstream,
    MetafieldShopifyGraphQlBulkStream,
    MetafieldShopifySubstream,
    ShopifyStream,
)


class Articles(IncrementalShopifyStreamWithDeletedEvents):
    data_field = "articles"
    cursor_field = "id"
    order_field = "id"
    filter_field = "since_id"
    deleted_events_api_name = "Article"


class MetafieldArticles(MetafieldShopifySubstream):
    parent_stream_class: object = Articles


class Blogs(IncrementalShopifyStreamWithDeletedEvents):
    cursor_field = "id"
    order_field = "id"
    data_field = "blogs"
    filter_field = "since_id"
    deleted_events_api_name = "Blog"


class MetafieldBlogs(MetafieldShopifySubstream):
    parent_stream_class: object = Blogs


class Customers(IncrementalShopifyStream):
    data_field = "customers"


class MetafieldCustomers(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldCustomer = MetafieldCustomer


class Orders(IncrementalShopifyStreamWithDeletedEvents):
    data_field = "orders"
    deleted_events_api_name = "Order"

    def request_params(
        self, stream_state: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None, **kwargs
    ) -> MutableMapping[str, Any]:
        params = super().request_params(stream_state=stream_state, next_page_token=next_page_token, **kwargs)
        if not next_page_token:
            params["status"] = "any"
        return params


class Disputes(IncrementalShopifyStream):
    data_field = "disputes"
    filter_field = "since_id"
    cursor_field = "id"
    order_field = "id"

    def path(self, **kwargs) -> str:
        return f"shopify_payments/{self.data_field}.json"


class MetafieldOrders(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldOrder = MetafieldOrder


class DraftOrders(IncrementalShopifyStream):
    data_field = "draft_orders"


class MetafieldDraftOrders(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldDraftOrder = MetafieldDraftOrder


class Products(IncrementalShopifyStreamWithDeletedEvents):
    use_cache = True
    data_field = "products"
    deleted_events_api_name = "Product"


class ProductsGraphQl(IncrementalShopifyStream):
    filter_field = "updatedAt"
    cursor_field = "updatedAt"
    data_field = "graphql"
    http_method = "POST"

    def request_params(
        self,
        stream_state: Optional[Mapping[str, Any]] = None,
        next_page_token: Optional[Mapping[str, Any]] = None,
        **kwargs,
    ) -> MutableMapping[str, Any]:
        return {}

    def request_body_json(
        self,
        stream_state: Mapping[str, Any],
        stream_slice: Optional[Mapping[str, Any]] = None,
        next_page_token: Optional[Mapping[str, Any]] = None,
    ) -> Optional[Mapping]:
        state_value = stream_state.get(self.filter_field)
        if state_value:
            filter_value = state_value
        else:
            filter_value = self.default_filter_field_value
        query = get_query_products(
            first=self.limit, filter_field=self.filter_field, filter_value=filter_value, next_page_token=next_page_token
        )
        return {"query": query}

    @staticmethod
    def next_page_token(response: requests.Response) -> Optional[Mapping[str, Any]]:
        page_info = response.json()["data"]["products"]["pageInfo"]
        has_next_page = page_info["hasNextPage"]
        if has_next_page:
            return page_info["endCursor"]
        else:
            return None

    @limiter.balance_rate_limit(api_type=ApiTypeEnum.graphql.value)
    def parse_response(self, response: requests.Response, **kwargs) -> Iterable[Mapping]:
        if response.status_code is requests.codes.OK:
            try:
                json_response = response.json()["data"]["products"]["nodes"]
                yield from self.produce_records(json_response)
            except RequestException as e:
                self.logger.warning(f"Unexpected error in `parse_ersponse`: {e}, the actual response data: {response.text}")


class MetafieldProducts(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldProduct = MetafieldProduct


class ProductImages(IncrementalShopifyNestedSubstream):
    parent_stream_class: object = Products
    nested_substream = "images"
    # add `product_id` to each nested subrecord
    mutation_map = {"product_id": "id"}


class MetafieldProductImages(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldProductImage = MetafieldProductImage


class ProductVariants(IncrementalShopifyNestedSubstream):
    parent_stream_class: object = Products
    nested_substream = "variants"
    # add `product_id` to each nested subrecord
    mutation_map = {"product_id": "id"}


class MetafieldProductVariants(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldProductVariant = MetafieldProductVariant


class AbandonedCheckouts(IncrementalShopifyStream):
    data_field = "checkouts"

    def request_params(
        self, stream_state: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None, **kwargs
    ) -> MutableMapping[str, Any]:
        params = super().request_params(stream_state=stream_state, next_page_token=next_page_token, **kwargs)
        # If there is a next page token then we should only send pagination-related parameters.
        if not next_page_token:
            params["status"] = "any"
        return params


class CustomCollections(IncrementalShopifyStreamWithDeletedEvents):
    data_field = "custom_collections"
    deleted_events_api_name = "Collection"


class SmartCollections(IncrementalShopifyStream):
    data_field = "smart_collections"


class MetafieldSmartCollections(MetafieldShopifySubstream):
    parent_stream_class: object = SmartCollections


class Collects(IncrementalShopifyStream):
    """
    Collects stream does not support Incremental Refresh based on datetime fields, only `since_id` is supported:
    https://shopify.dev/docs/admin-api/rest/reference/products/collect

    The Collect stream is the link between Products and Collections, if the Collection is created for Products,
    the `collect` record is created, it's reasonable to Full Refresh all collects. As for Incremental refresh -
    we would use the since_id specificaly for this stream.
    """

    data_field = "collects"
    cursor_field = "id"
    order_field = "id"
    filter_field = "since_id"


class Collections(IncrementalShopifyGraphQlBulkStream):
    bulk_query: Collection = Collection

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        # convert dates from ISO-8601 to RFC-3339
        record["published_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "published_at")
        record["updated_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "updated_at")
        # remove leftovers
        record.pop(BULK_PARENT_KEY, None)

        yield record


class MetafieldCollections(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldCollection = MetafieldCollection


class BalanceTransactions(IncrementalShopifyStream):

    """
    PaymentsTransactions stream does not support Incremental Refresh based on datetime fields, only `since_id` is supported:
    https://shopify.dev/api/admin-rest/2021-07/resources/transactions
    """

    data_field = "transactions"
    cursor_field = "id"
    order_field = "id"
    filter_field = "since_id"

    def path(self, **kwargs) -> str:
        return f"shopify_payments/balance/{self.data_field}.json"


class OrderRefunds(IncrementalShopifyNestedSubstream):
    parent_stream_class: object = Orders
    # override default cursor field
    cursor_field = "created_at"
    nested_substream = "refunds"


class OrderRisks(IncrementalShopifySubstream):
    parent_stream_class: object = Orders
    slice_key = "order_id"
    data_field = "risks"
    cursor_field = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs) -> str:
        order_id = stream_slice["order_id"]
        return f"orders/{order_id}/{self.data_field}.json"


class Transactions(IncrementalShopifySubstream):
    parent_stream_class: object = Orders
    slice_key = "order_id"
    data_field = "transactions"
    cursor_field = "created_at"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs) -> str:
        order_id = stream_slice["order_id"]
        return f"orders/{order_id}/{self.data_field}.json"


class TransactionsGraphql(IncrementalShopifyGraphQlBulkStream):
    bulk_query: Transaction = Transaction
    cursor_field = "created_at"

    def get_json_schema(self) -> Mapping[str, Any]:
        """
        This stream has the same schema as `Transactions` stream, except of:
         - fields: [ `device_id, source_name, user_id, location_id` ]

           Specifically:
            - `user_id` field requires `Shopify Plus` / be authorised via `Financialy Embedded App`.
            - additional `read_users` scope is required https://shopify.dev/docs/api/usage/access-scopes#authenticated-access-scopes
        """
        return ResourceSchemaLoader(package_name_from_class(Transactions)).get_schema("transactions")

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        transactions_key = "transactions"
        if transactions_key in record.keys():
            for transaction in record.get(transactions_key):
                # populate parent record keys
                transaction["order_id"] = record.get("id")
                transaction["currency"] = record.get("currency")
                yield self.query.prep_transaction(transaction)


class TenderTransactions(IncrementalShopifyStream):
    data_field = "tender_transactions"
    cursor_field = "processed_at"
    filter_field = "processed_at_min"


class Pages(IncrementalShopifyStreamWithDeletedEvents):
    data_field = "pages"
    deleted_events_api_name = "Page"


class MetafieldPages(MetafieldShopifySubstream):
    parent_stream_class: object = Pages


class PriceRules(IncrementalShopifyStreamWithDeletedEvents):
    data_field = "price_rules"
    deleted_events_api_name = "PriceRule"


class DiscountCodes(IncrementalShopifyGraphQlBulkStream):
    bulk_query: DiscountCode = DiscountCode

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        # resolve parent id from `str` to `int`
        record["price_rule_id"] = self.bulk_job.tools.resolve_str_id(record.get("price_rule_id"))
        # convert dates from ISO-8601 to RFC-3339
        record["created_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "created_at")
        record["updated_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "updated_at")
        # remove leftovers
        record.pop("code_discount", None)
        record.pop(BULK_PARENT_KEY, None)

        yield record


class Locations(ShopifyStream):
    """
    The location API does not support any form of filtering.
    https://shopify.dev/api/admin-rest/2021-07/resources/location

    Therefore, only FULL_REFRESH mode is supported.
    """

    data_field = "locations"


class MetafieldLocations(MetafieldShopifyGraphQlBulkStream):
    bulk_query: MetafieldLocation = MetafieldLocation
    filter_field = None


class InventoryLevels(IncrementalShopifyGraphQlBulkStream):
    bulk_query: InventoryLevel = InventoryLevel

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        # resolve `inventory_item_id` to root lvl +  resolve to int
        record["inventory_item_id"] = self.bulk_job.tools.resolve_str_id(record.get("item", {}).get("inventory_item_id"))
        # add `location_id` from `__parentId`
        record["location_id"] = self.bulk_job.tools.resolve_str_id(record[BULK_PARENT_KEY])
        # make composite `id` from `location_id|inventory_item_id`
        record["id"] = "|".join((str(record.get("location_id", "")), str(record.get("inventory_item_id", ""))))
        # convert dates from ISO-8601 to RFC-3339
        record["updated_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "updated_at")
        # remove leftovers
        record.pop("item", None)
        record.pop(BULK_PARENT_KEY, None)
        yield record


class InventoryItems(IncrementalShopifyGraphQlBulkStream):
    bulk_query: InventoryItem = InventoryItem

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        # resolve `cost` to root lvl as `number`
        record["cost"] = float(record.get("unit_cost", {}).get("cost"))
        # add empty `country_harmonized_system_codes` array, if missing for record
        if "country_harmonized_system_codes" not in record.keys():
            record["country_harmonized_system_codes"] = []
        # convert dates from ISO-8601 to RFC-3339
        record["created_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "created_at")
        record["updated_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "updated_at")
        # remove leftovers
        record.pop("unit_cost", None)

        yield record


class FulfillmentOrders(IncrementalShopifyGraphQlBulkStream):
    bulk_query: FulfillmentOrder = FulfillmentOrder

    def custom_transform(self, record: Mapping[str, Any]) -> Iterable[Mapping[str, Any]]:
        """
        Custom transformation for produced records.
        """
        # convert dates from ISO-8601 to RFC-3339
        record["fulfill_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "fulfill_at")
        record["created_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "created_at")
        record["updated_at"] = self.bulk_job.tools.from_iso8601_to_rfc3339(record, "updated_at")
        # delivery method
        delivery_method = record.get("delivery_method", {})
        if delivery_method:
            record["delivery_method"]["min_delivery_date_time"] = self.bulk_job.tools.from_iso8601_to_rfc3339(
                delivery_method, "min_delivery_date_time"
            )
            record["delivery_method"]["max_delivery_date_time"] = self.bulk_job.tools.from_iso8601_to_rfc3339(
                delivery_method, "max_delivery_date_time"
            )

        yield record


class Fulfillments(IncrementalShopifyNestedSubstream):
    parent_stream_class: object = Orders
    nested_substream = "fulfillments"


class Shop(ShopifyStream):
    data_field = "shop"


class MetafieldShops(IncrementalShopifyStream):
    data_field = "metafields"


class CustomerSavedSearch(IncrementalShopifyStream):
    api_version = "2022-01"
    cursor_field = "id"
    order_field = "id"
    data_field = "customer_saved_searches"
    filter_field = "since_id"


class CustomerAddress(IncrementalShopifyNestedSubstream):
    """
    https://shopify.dev/docs/api/admin-rest/2023-10/resources/customer#resource-object
    """

    parent_stream_class: object = Customers
    cursor_field = "id"
    nested_substream = "addresses"


class Countries(ShopifyStream):
    data_field = "countries"
