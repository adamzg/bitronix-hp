Bitronix-HP (High Performance)
==============================
This is a very lightly modified fork of bitronix-hp, hastily updated to account for the fact that MySQL doesn't correctly support XA.  It blithly rolls back changes made in 2.2 to the rollback behavior to make the XA failures continue to work with MySQL.

