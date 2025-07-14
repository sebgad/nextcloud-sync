# Nextcloud DAV sync

## Synchronization cases

- A: File in filesystem
- abcdef...z: LastModified timestamp
- x: File is not available


| client   | remote   | result                                                                    | SQL statement |
|----------|----------|---------------------------------------------------------------------------|---------------|
| Aa -> Ab | Aa -> Aa | client -> remote                                                          | 1)            |
| x  -> Aa | x  -> x  | client -> remote                                                          | 2)            |
| Aa -> Ab | Aa -> Ac | conflict<br/> client->remote (with suffix)<br/> remote->client (override) | 3)            |
| Aa -> Aa | Aa -> Ab | remote -> client                                                          | 4)            |
| x  -> x  | x  -> Aa | remote -> client                                                          | 5)            |
| Aa -> x  | Aa -> Aa | client -> remote                                                          | 6)            |
| Aa -> x  | Aa -> Ab | conflict<br/> client->remote (with suffix)<br/> remote->client (override) | 7)            |
| Aa -> Aa | Aa -> x  | remote -> client                                                          | 8)            |
| Aa -> Ab | Aa -> x  | conflict<br/> client->remote (with suffix)<br/> remote->client (override) | 9)            |


### SQL statement 1

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries 
  - where the number of unique remoteLastModified is only 1 AND
  - where the number of unique localLastModified is two -> local file changed AND
  - where localLastModified has no entries with 0

``` sql
SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
    )
GROUP BY localPath
HAVING
COUNT(DISTINCT remoteLastModified) = 1
AND
COUNT(DISTINCT localLastModified) = 2
AND
localLastModified != 0
```

### SQL statement 2

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries
  - where the number of captured entries for this file is only 1 AND
  - where the remoteLastModified equals to 0 (No timestamp is set, because remote file n.a.)

``` sql
SELECT *, COUNT(captured)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
)
GROUP by localPath
HAVING COUNT(captured) = 1
AND
remoteLastModified = 0;
```

### SQL statement 3

systematic:
- Take the most recent two captures of the local and file remote file system
- Filter entries
  - where the number of unique remoteLastModified entries equals 2 AND
  - where the number of unique localLastModified entries equals 2
- The conflict will be solved by copying the local file with prefix conflict_ and downloading the remote file

``` sql
SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
    )
GROUP BY localPath
HAVING
COUNT(DISTINCT remoteLastModified) = 2
AND
COUNT(DISTINCT localLastModified) = 2
```

### SQL statement 4

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries
    - where the number of unique remoteLastModified is two AND
    - where the number of unique localLastModified is one -> remote file changed

``` sql
SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
    )
GROUP BY localPath
HAVING
COUNT(DISTINCT remoteLastModified) = 2
AND
COUNT(DISTINCT localLastModified) = 1
```

### SQL statement 5

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries
    - where the number of captured entries for this file is only 1 AND
    - where the localLastModified equals to 0 (No timestamp is set, because remote file n.a.)

``` sql
SELECT *, COUNT(captured)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
)
GROUP by localPath
HAVING COUNT(captured) = 1
AND
localLastModified = 0;
```

### SQL statement 6

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries
  - where the number of unique remoteLastModified is only 1 AND
  - where the number of unique localLastModified is two -> local file changed AND
  - where an entry of localLastModified equals zero

``` sql
SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
    )
GROUP BY localPath
HAVING
COUNT(DISTINCT remoteLastModified) = 1
AND
COUNT(DISTINCT localLastModified) = 2
AND
localLastModified == 0
```

### SQL statement 8

systematic:
- Take the most recent two captures of the local and remote file system
- Filter entries
  - where the number of unique remoteLastModified is only 1 AND
  - where the number of unique localLastModified is two -> local file changed AND
  - where an entry of localLastModified equals zero

``` sql
SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
FROM syncTable
WHERE captured IN (
    SELECT DISTINCT captured
    FROM syncTable
    ORDER BY captured DESC
    LIMIT 2
    )
GROUP BY localPath
HAVING
COUNT(DISTINCT remoteLastModified) = 2
AND
COUNT(DISTINCT localLastModified) = 1
AND
remoteLastModified == 0
```