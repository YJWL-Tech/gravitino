"""
Copyright 2024 Datastrato Pvt Ltd.
This software is licensed under the Apache License version 2.
"""

import base64
import os

from gravitino.auth.auth_constants import AuthConstants
from gravitino.auth.auth_data_provider import AuthDataProvider


class SimpleAuthProvider(AuthDataProvider):
    """SimpleAuthProvider will use the environment variable `GRAVITINO_USER` or
    the user of the system to generate a basic token for every request.

    """

    _token: bytes

    def __init__(self, token: str = None):
        if token is not None:
            user_information = token
        elif os.environ.get("GRAVITINO_TOKEN") is not None:
            user_information = os.environ.get("GRAVITINO_TOKEN")
        else:
            gravitino_user = os.environ.get("GRAVITINO_USER")
            if gravitino_user is None or len(gravitino_user) == 0:
                gravitino_user = os.environ.get("user.name")

            if gravitino_user is None or len(gravitino_user) == 0:
                gravitino_user = "anonymous"

            user_information = f"{gravitino_user}:dummy"
        self._token = (
            AuthConstants.AUTHORIZATION_BASIC_HEADER
            + base64.b64encode(user_information.encode("utf-8")).decode("utf-8")
        ).encode("utf-8")

    def has_token_data(self) -> bool:
        return True

    def get_token_data(self) -> bytes:
        return self._token

    def close(self):
        pass
