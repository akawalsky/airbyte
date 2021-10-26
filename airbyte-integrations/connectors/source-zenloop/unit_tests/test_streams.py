#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#

from http import HTTPStatus
from unittest.mock import MagicMock

import pytest
from source_zenloop.source import ZenloopStream
import requests
import json


@pytest.fixture
def patch_base_class(mocker):
    # Mock abstract methods to enable instantiating abstract class
    mocker.patch.object(ZenloopStream, "path", "v0/example_endpoint")
    mocker.patch.object(ZenloopStream, "primary_key", "test_primary_key")
    mocker.patch.object(ZenloopStream, "__abstractmethods__", set())


def test_request_params(patch_base_class, config):
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    inputs = {"stream_slice": None, "stream_state": None, "next_page_token": {'page': "1"}}
    expected_params = {'page': "1"}
    assert stream.request_params(**inputs) == expected_params


def test_next_page_token(patch_base_class, config):
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    inputs = {"response": MagicMock()}
    inputs["response"].json.return_value = {"meta": {"page": 1, "per_page": 12, "total": 8}}
    expected_token = None
    assert stream.next_page_token(**inputs) == expected_token


def test_parse_response(patch_base_class, config):
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    response = MagicMock()
    response.json.return_value = {"answers": [{"id": 123, "name": "John Doe"}]}
    inputs = {"response": response}
    expected_parsed_object = {"answers": [{"id": 123, "name": "John Doe"}]}
    assert next(stream.parse_response(**inputs)) == expected_parsed_object


def test_request_headers(patch_base_class, config):
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    inputs = {"stream_slice": None, "stream_state": None, "next_page_token": None}
    expected_headers = {}
    assert stream.request_headers(**inputs) == expected_headers


def test_http_method(patch_base_class, config):
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    expected_method = "GET"
    assert stream.http_method == expected_method


@pytest.mark.parametrize(
    ("http_status", "should_retry"),
    [
        (HTTPStatus.OK, False),
        (HTTPStatus.BAD_REQUEST, False),
        (HTTPStatus.TOO_MANY_REQUESTS, True),
        (HTTPStatus.INTERNAL_SERVER_ERROR, True),
    ],
)
def test_should_retry(patch_base_class, config, http_status, should_retry):
    response_mock = MagicMock()
    response_mock.status_code = http_status
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    assert stream.should_retry(response_mock) == should_retry


def test_backoff_time(patch_base_class, config):
    response_mock = MagicMock()
    stream = ZenloopStream(config["api_token"], config["date_from"], config["public_hash_id"])
    expected_backoff_time = None
    assert stream.backoff_time(response_mock) == expected_backoff_time
