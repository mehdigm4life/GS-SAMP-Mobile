### no aborts on the streaming path
```
!0  hooks.cpp must not contain: rled error
1  hooks.cpp: memset(pDest, 0, (size_t)(pEndOfDest - pDest));
!0  main.h must not contain: throw std::runtime_error( "Error during formatting." )
1  game.cpp: catch (const std::exception& exception)
1  actorpool.cpp: catch (const std::exception& exception)
```
