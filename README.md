# Nextcloud DAV sync

## Synchronization cases

- A: File in filesystem
- abcdef...z: LastModified timestamp
- x: File is not available


| client   | remote   | result                                                                    |
|----------|----------|---------------------------------------------------------------------------|
| Aa -> Ab | Aa -> Aa | client -> remote                                                          |
| x  -> Aa | x  -> x  | client -> remote                                                          |
| Aa -> Ab | Aa -> Ac | conflict<br/> client->remote (with suffix)<br/> remote->client (override) |
| Aa -> Aa | Aa -> Ab | remote -> client                                                          |
| x  -> x  | x  -> Aa | remote -> client                                                          |
| Aa -> x  | Aa -> Aa | client -> remote                                                          |
| Aa -> x  | Aa -> Ab | conflict<br/> client->remote (with suffix)<br/> remote->client (override) |
| Aa -> Aa | Aa -> x  | remote -> client                                                          |
| Aa -> Ab | Aa -> x  | conflict<br/> client->remote (with suffix)<br/> remote->client (override) |

